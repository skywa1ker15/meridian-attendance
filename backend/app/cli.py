"""Operational command line: ``python -m app.cli <command>``.

Commands
  create-admin   create a superadmin/admin account (interactive or with flags)
  seed-policy    ensure the policy row and the PLACEHOLDER workplace exist
  seed-demo      DEVELOPMENT ONLY: demo departments, employees and attendance
                 (--admin-identifier/--admin-password also ensure a demo superadmin)
  jobs run-once  run the scheduler jobs once (cron alternative to the in-process loop)
  revoke-all-sessions  sign everybody out (bump token_version, revoke refresh tokens)
"""
from __future__ import annotations

import argparse
import getpass
import json
import sys
import uuid
from datetime import timedelta

from sqlalchemy import func, select

from app.core import timeutil
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import (
    generate_temporary_password,
    hash_password,
    validate_password_policy,
)
from app.db.session import get_sessionmaker
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    Department,
    ManualRequest,
    User,
    Workplace,
)
from app.services.attendance import compute_session_metrics
from app.services.config_service import get_policy

PLACEHOLDER_WORKPLACE_NAME = "Meridian Group — Umhlanga (PLACEHOLDER — set real coordinates)"
DEMO_TAG = "[DEMO DATA]"


def ensure_policy_and_workplace(db) -> tuple[bool, bool]:
    """Returns (policy_created, workplace_created). Never invents coordinates."""
    from app.models import AttendancePolicy

    policy_created = db.get(AttendancePolicy, 1) is None
    get_policy(db)
    workplace_created = False
    if db.execute(select(func.count()).select_from(Workplace)).scalar_one() == 0:
        db.add(
            Workplace(
                id=uuid.uuid4(),
                name=PLACEHOLDER_WORKPLACE_NAME,
                address="Umhlanga, KwaZulu-Natal (PLACEHOLDER — confirm the street address)",
                latitude=0.0,
                longitude=0.0,
                coordinates_confirmed=False,
                radius_m=150,
                accuracy_threshold_m=100,
                wifi_bssids=[],
            )
        )
        workplace_created = True
    db.commit()
    return policy_created, workplace_created


def cmd_create_admin(args) -> int:
    employee_number = args.employee_number or input("Employee number: ").strip()
    email = args.email or input("Email: ").strip()
    first = args.first_name or input("First name: ").strip()
    last = args.last_name or input("Last name: ").strip()
    generated = False
    if args.generate:
        password, generated = generate_temporary_password(16), True
    elif args.password:
        password = args.password
    else:
        password = getpass.getpass("Password: ")
        if password != getpass.getpass("Repeat password: "):
            print("Passwords do not match.", file=sys.stderr)
            return 2
    try:
        validate_password_policy(password, [email, employee_number])
    except ApiError as exc:
        print(f"Password rejected: {exc.message}", file=sys.stderr)
        return 2
    with get_sessionmaker()() as db:
        ensure_policy_and_workplace(db)
        clash = db.execute(select(User).where((User.employee_number == employee_number) | (func.lower(User.email) == email.lower()))).scalars().first()
        if clash is not None:
            print("A user with that employee number or email already exists.", file=sys.stderr)
            return 1
        user = User(id=uuid.uuid4(), employee_number=employee_number, email=email, first_name=first, last_name=last, role=args.role, status="active", password_hash=hash_password(password), must_change_password=generated)
        db.add(user)
        from app.services import audit

        audit.record(db, action="employee.create", actor=None, actor_type="system", entity_type="user", entity_id=user.id, after={"employee_number": employee_number, "role": args.role, "via": "cli"})
        db.commit()
    print(f"Created {args.role} {employee_number} <{email}>.")
    if generated:
        print(f"Temporary password (shown once, must be changed at first sign-in): {password}")
    return 0


def cmd_seed_policy(_args) -> int:
    with get_sessionmaker()() as db:
        p, w = ensure_policy_and_workplace(db)
    print(f"policy: {'created' if p else 'exists'}; workplace: {'placeholder created' if w else 'exists'}")
    if w:
        print("NOTE: the workplace has PLACEHOLDER coordinates (0,0) and coordinates_confirmed=false. Set the real coordinates in the dashboard before go-live.")
    return 0


def cmd_seed_demo(args) -> int:
    settings = get_settings()
    if settings.app_env == "production":
        print("Refusing: seed-demo never runs with APP_ENV=production.", file=sys.stderr)
        return 2
    if settings.app_env != "development" and not args.i_know_this_is_demo_data:
        print("Refusing: seed-demo only runs with APP_ENV=development (or --i-know-this-is-demo-data outside production).", file=sys.stderr)
        return 2
    if bool(args.admin_identifier) != bool(args.admin_password):
        print("--admin-identifier and --admin-password must be given together.", file=sys.stderr)
        return 2
    rng_names = [("Demo", "Employee A"), ("Demo", "Employee B"), ("Demo", "Employee C"), ("Demo", "Employee D"), ("Demo", "Manager")]
    with get_sessionmaker()() as db:
        ensure_policy_and_workplace(db)
        policy = get_policy(db)
        wp = db.execute(select(Workplace).order_by(Workplace.created_at)).scalars().first()
        deps = {}
        for name, code in (("Demo Finance", "DFIN"), ("Demo Operations", "DOPS")):
            dep = db.execute(select(Department).where(Department.code == code)).scalar_one_or_none()
            if dep is None:
                dep = Department(id=uuid.uuid4(), name=name, code=code)
                db.add(dep)
                db.flush()
            deps[code] = dep
        password = args.password or generate_temporary_password(14)
        users = []
        for i, (first, last) in enumerate(rng_names, start=1):
            number = f"DEMO-{i:03d}"
            u = db.execute(select(User).where(User.employee_number == number)).scalar_one_or_none()
            if u is None:
                u = User(id=uuid.uuid4(), employee_number=number, email=f"demo{i}@demo.invalid", first_name=first, last_name=last, role="manager" if last == "Manager" else "employee", department_id=(deps["DFIN"] if i % 2 else deps["DOPS"]).id, password_hash=hash_password(password), must_change_password=False)
                db.add(u)
                db.flush()
            users.append(u)
        today = timeutil.today_local()
        created = 0
        for back in range(1, 11):
            day = today - timedelta(days=back)
            if day.isoweekday() not in (policy.working_days or [1, 2, 3, 4, 5]):
                continue
            for idx, u in enumerate(users[:4]):
                if (back + idx) % 7 == 0:
                    continue  # deterministic absences
                if db.execute(select(AttendanceSession.id).where(AttendanceSession.user_id == u.id, AttendanceSession.work_date == day)).first():
                    continue
                start_min = 7 * 60 + 45 + ((back * 7 + idx * 13) % 50)
                cin = timeutil.local_datetime(day, f"{start_min // 60:02d}:{start_min % 60:02d}").astimezone(timeutil.UTC)
                cout = cin + timedelta(hours=9, minutes=(idx * 11) % 40)
                s = AttendanceSession(id=uuid.uuid4(), user_id=u.id, workplace_id=wp.id if wp else None, work_date=day, status="closed", clock_in_at=cin, clock_out_at=cout, is_manual=True, notes=f"{DEMO_TAG} generated by seed-demo")
                if back == 1 and idx == 3:
                    s.status, s.clock_out_at = "missing_clock_out", None
                compute_session_metrics(s, policy)
                db.add(s)
                created += 1
        if wp is not None and not db.execute(select(AttendanceEvent.id).where(AttendanceEvent.client_event_id == "demo-pending-1")).first():
            now = timeutil.now_utc()
            db.add(AttendanceEvent(id=uuid.uuid4(), user_id=users[0].id, workplace_id=wp.id, client_event_id="demo-pending-1", event_type="arrival", source="geofence", status="under_review", detected_at=now - timedelta(minutes=30), received_at=now - timedelta(minutes=30), official_at=now - timedelta(minutes=30), expires_at=now + timedelta(hours=6), verification={"decision": "review", "reason": "workplace_coordinates_unconfirmed", "notes": [f"{DEMO_TAG} sample event"]}, review_reason="workplace_coordinates_unconfirmed", note=DEMO_TAG))
            db.add(ManualRequest(id=uuid.uuid4(), user_id=users[1].id, client_request_id="demo-request-1", type="missed_clock_out", status="pending", work_date=today - timedelta(days=1), requested_clock_out=now - timedelta(days=1), reason=f"{DEMO_TAG} sample request"))
        if args.admin_identifier:
            ident = args.admin_identifier.strip()
            try:
                validate_password_policy(args.admin_password, [ident])
            except ApiError as exc:
                print(f"Admin password rejected: {exc.message}", file=sys.stderr)
                return 2
            admin = db.execute(select(User).where((func.lower(User.email) == ident.lower()) | (User.employee_number == ident))).scalars().first()
            if admin is None:
                is_email = "@" in ident
                admin = User(id=uuid.uuid4(), employee_number="DEMO-ADMIN" if is_email else ident, email=ident if is_email else "demo-admin@demo.invalid", first_name="Demo", last_name="Administrator", role="superadmin")
                db.add(admin)
            admin.password_hash = hash_password(args.admin_password)
            admin.status = "active"
            admin.must_change_password = False
            print(f"Demo superadmin ready: {ident}")
        db.commit()
    print(f"Demo data ready ({created} sessions). Demo users DEMO-001..DEMO-005 share the password: {password}")
    print("All demo records are labelled DEMO / [DEMO DATA]. Never run this against production.")
    return 0


def cmd_jobs(args) -> int:
    if args.job_command != "run-once":
        print("usage: python -m app.cli jobs run-once", file=sys.stderr)
        return 2
    from app.services.scheduler import run_once

    print(json.dumps(run_once()))
    return 0


def cmd_revoke_all_sessions(_args) -> int:
    from sqlalchemy import update

    from app.models import RefreshToken
    from app.services import audit

    with get_sessionmaker()() as db:
        now = timeutil.now_utc()
        users = db.execute(update(User).values(token_version=User.token_version + 1)).rowcount or 0
        tokens = db.execute(update(RefreshToken).where(RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason="revoke_all_sessions")).rowcount or 0
        audit.record(db, action="auth.revoke_all_sessions", actor=None, actor_type="system", entity_type="system", after={"users": users, "refresh_tokens": tokens, "via": "cli"})
        db.commit()
    print(f"Revoked {tokens} refresh tokens; invalidated access tokens of {users} users.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python -m app.cli", description="Meridian Attendance operations CLI")
    sub = p.add_subparsers(dest="command", required=True)
    a = sub.add_parser("create-admin", help="create an administrator account")
    a.add_argument("--employee-number")
    a.add_argument("--email")
    a.add_argument("--first-name")
    a.add_argument("--last-name")
    a.add_argument("--role", choices=["admin", "superadmin"], default="superadmin")
    g = a.add_mutually_exclusive_group()
    g.add_argument("--password", help="avoid on shared machines (shell history); prefer the prompt or --generate")
    g.add_argument("--generate", action="store_true", help="generate a temporary password (must be changed at first sign-in)")
    a.set_defaults(func=cmd_create_admin)
    sp = sub.add_parser("seed-policy", help="ensure policy row and placeholder workplace")
    sp.set_defaults(func=cmd_seed_policy)
    d = sub.add_parser("seed-demo", help="DEV ONLY: create clearly labelled demo data")
    d.add_argument("--i-know-this-is-demo-data", action="store_true")
    d.add_argument("--password", help="password for demo users (default: generated)")
    d.add_argument("--admin-identifier", help="email or employee number of a demo superadmin to create/update")
    d.add_argument("--admin-password", help="password for that demo superadmin")
    d.set_defaults(func=cmd_seed_demo)
    j = sub.add_parser("jobs", help="background jobs")
    jsub = j.add_subparsers(dest="job_command", required=True)
    jsub.add_parser("run-once", help="run all scheduler jobs once")
    j.set_defaults(func=cmd_jobs)
    r = sub.add_parser("revoke-all-sessions", help="sign out every user on every device")
    r.set_defaults(func=cmd_revoke_all_sessions)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":
    sys.exit(main())
