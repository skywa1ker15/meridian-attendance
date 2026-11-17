"""Reports: eleven report types, each computed from the database and rendered as
JSON, CSV, XLSX (openpyxl) or PDF (reportlab)."""
from __future__ import annotations

import csv
import io
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.core import timeutil
from app.core.errors import ApiError
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    Department,
    ManualRequest,
    User,
)
from app.services.config_service import get_policy

REPORT_TYPES = ("daily", "weekly", "monthly", "employee", "department", "late-arrivals", "absences", "missing-clock-outs", "manual-attendance", "pending-confirmations", "working-hours")
FORMATS = ("json", "csv", "xlsx", "pdf")


@dataclass
class ReportFilters:
    date_from: date
    date_to: date
    employee_id: uuid.UUID | None = None
    department_id: uuid.UUID | None = None
    status: str | None = None


@dataclass
class Report:
    name: str
    filters: ReportFilters
    columns: list[tuple[str, str]]
    rows: list[dict[str, Any]] = field(default_factory=list)
    summary: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        return {
            "report": self.name,
            "from": self.filters.date_from.isoformat(),
            "to": self.filters.date_to.isoformat(),
            "generated_at": timeutil.utc_iso(timeutil.now_utc()),
            "columns": [{"key": k, "label": label} for k, label in self.columns],
            "rows": self.rows,
            "summary": self.summary,
        }


# --------------------------------------------------------------------------------------
# data access
# --------------------------------------------------------------------------------------


def _hm(minutes: int | None) -> str:
    minutes = int(minutes or 0)
    return f"{minutes // 60:d}:{minutes % 60:02d}"


def _users(db: Session, f: ReportFilters, include_inactive: bool = True) -> list[User]:
    stmt = select(User).options(selectinload(User.department)).where(User.erased_at.is_(None))
    if f.employee_id is not None:
        stmt = stmt.where(User.id == f.employee_id)
    if f.department_id is not None:
        stmt = stmt.where(User.department_id == f.department_id)
    if not include_inactive:
        stmt = stmt.where(User.status == "active")
    return list(db.execute(stmt.order_by(User.last_name, User.first_name)).scalars())


def _sessions(db: Session, f: ReportFilters, user_ids: list[uuid.UUID]) -> list[AttendanceSession]:
    if not user_ids:
        return []
    stmt = (
        select(AttendanceSession)
        .options(selectinload(AttendanceSession.user).selectinload(User.department), selectinload(AttendanceSession.workplace))
        .where(AttendanceSession.user_id.in_(user_ids), AttendanceSession.work_date >= f.date_from, AttendanceSession.work_date <= f.date_to)
        .order_by(AttendanceSession.work_date, AttendanceSession.clock_in_at)
    )
    if f.status:
        stmt = stmt.where(AttendanceSession.status == f.status)
    return list(db.execute(stmt).scalars())


def _dept(u: User) -> str:
    return u.department.name if u.department else ""


def _emp_cols() -> list[tuple[str, str]]:
    return [("employee_number", "Employee #"), ("employee", "Employee"), ("department", "Department")]


def _emp_row(u: User) -> dict[str, Any]:
    return {"employee_id": str(u.id), "employee_number": u.employee_number, "employee": u.full_name, "department": _dept(u)}


def _working_days(db: Session, f: ReportFilters) -> list[date]:
    days = set(get_policy(db).working_days or [1, 2, 3, 4, 5])
    return [d for d in timeutil.daterange(f.date_from, f.date_to) if d.isoweekday() in days]


# --------------------------------------------------------------------------------------
# report builders
# --------------------------------------------------------------------------------------


def _daily(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    sessions = _sessions(db, f, [u.id for u in users])
    cols = [("work_date", "Date"), *_emp_cols(), ("clock_in", "Clock in"), ("clock_out", "Clock out"), ("worked", "Worked (h:mm)"), ("worked_minutes", "Worked (min)"), ("overtime_minutes", "Overtime (min)"), ("late", "Late"), ("late_minutes", "Late (min)"), ("status", "Status"), ("manual", "Manual"), ("corrected", "Corrected")]
    r = Report("daily", f, cols)
    for s in sessions:
        r.rows.append({"work_date": s.work_date.isoformat(), **_emp_row(s.user), "clock_in": timeutil.local_time_str(s.clock_in_at), "clock_out": timeutil.local_time_str(s.clock_out_at), "worked": _hm(s.worked_minutes), "worked_minutes": s.worked_minutes, "overtime_minutes": s.overtime_minutes, "late": "yes" if s.is_late else "no", "late_minutes": s.late_minutes, "status": s.status, "manual": "yes" if s.is_manual else "no", "corrected": "yes" if s.corrected else "no", "session_id": str(s.id)})
    r.summary = {"sessions": len(sessions), "employees_present": len({s.user_id for s in sessions}), "late": sum(1 for s in sessions if s.is_late), "worked_minutes": sum(s.worked_minutes for s in sessions), "overtime_minutes": sum(s.overtime_minutes for s in sessions)}
    return r


def _period(db: Session, f: ReportFilters, name: str, key_fn, label: str) -> Report:
    users = _users(db, f)
    sessions = _sessions(db, f, [u.id for u in users])
    agg: dict[tuple[uuid.UUID, str], dict[str, Any]] = {}
    by_user = {u.id: u for u in users}
    for s in sessions:
        k = (s.user_id, key_fn(s.work_date))
        a = agg.setdefault(k, {"days": set(), "late": 0, "worked": 0, "overtime": 0, "missing": 0, "manual": 0})
        a["days"].add(s.work_date)
        a["late"] += 1 if s.is_late else 0
        a["worked"] += s.worked_minutes
        a["overtime"] += s.overtime_minutes
        a["missing"] += 1 if s.status == "missing_clock_out" else 0
        a["manual"] += 1 if s.is_manual else 0
    cols = [("period", label), *_emp_cols(), ("days_present", "Days present"), ("late_days", "Late days"), ("worked", "Worked (h:mm)"), ("worked_minutes", "Worked (min)"), ("overtime_minutes", "Overtime (min)"), ("missing_clock_outs", "Missing clock-outs"), ("manual_sessions", "Manual sessions")]
    r = Report(name, f, cols)
    for (uid, period), a in sorted(agg.items(), key=lambda kv: (kv[0][1], by_user[kv[0][0]].last_name)):
        r.rows.append({"period": period, **_emp_row(by_user[uid]), "days_present": len(a["days"]), "late_days": a["late"], "worked": _hm(a["worked"]), "worked_minutes": a["worked"], "overtime_minutes": a["overtime"], "missing_clock_outs": a["missing"], "manual_sessions": a["manual"]})
    r.summary = {"rows": len(r.rows), "worked_minutes": sum(x["worked_minutes"] for x in r.rows), "overtime_minutes": sum(x["overtime_minutes"] for x in r.rows), "late_days": sum(x["late_days"] for x in r.rows)}
    return r


def _weekly(db: Session, f: ReportFilters) -> Report:
    return _period(db, f, "weekly", lambda d: f"{d.isocalendar()[0]}-W{d.isocalendar()[1]:02d}", "Week")


def _monthly(db: Session, f: ReportFilters) -> Report:
    return _period(db, f, "monthly", lambda d: d.strftime("%Y-%m"), "Month")


def _employee(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    sessions = _sessions(db, f, [u.id for u in users])
    wdays = _working_days(db, f)
    per: dict[uuid.UUID, list[AttendanceSession]] = defaultdict(list)
    for s in sessions:
        per[s.user_id].append(s)
    cols = [*_emp_cols(), ("status", "Account"), ("working_days", "Working days"), ("days_present", "Days present"), ("days_absent", "Days absent"), ("late_days", "Late days"), ("worked", "Worked (h:mm)"), ("worked_minutes", "Worked (min)"), ("overtime_minutes", "Overtime (min)"), ("avg_minutes_per_day", "Avg min/day"), ("missing_clock_outs", "Missing clock-outs"), ("manual_sessions", "Manual sessions")]
    r = Report("employee", f, cols)
    today = timeutil.today_local()
    for u in users:
        ss = per.get(u.id, [])
        present = {s.work_date for s in ss}
        created = timeutil.local_date(u.created_at) if u.created_at else f.date_from
        expected = [d for d in wdays if d >= created and d < today]
        absent = [d for d in expected if d not in present]
        worked = sum(s.worked_minutes for s in ss)
        r.rows.append({**_emp_row(u), "status": u.status, "working_days": len(expected), "days_present": len(present), "days_absent": len(absent), "late_days": sum(1 for s in ss if s.is_late), "worked": _hm(worked), "worked_minutes": worked, "overtime_minutes": sum(s.overtime_minutes for s in ss), "avg_minutes_per_day": round(worked / len(present)) if present else 0, "missing_clock_outs": sum(1 for s in ss if s.status == "missing_clock_out"), "manual_sessions": sum(1 for s in ss if s.is_manual)})
    r.summary = {"employees": len(users), "worked_minutes": sum(x["worked_minutes"] for x in r.rows), "late_days": sum(x["late_days"] for x in r.rows), "days_absent": sum(x["days_absent"] for x in r.rows)}
    return r


def _department(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f, include_inactive=False)
    sessions = _sessions(db, f, [u.id for u in users])
    wdays = [d for d in _working_days(db, f) if d < timeutil.today_local()]
    deps = {d.id: d for d in db.execute(select(Department)).scalars()}
    groups: dict[uuid.UUID | None, list[User]] = defaultdict(list)
    for u in users:
        groups[u.department_id].append(u)
    per_user: dict[uuid.UUID, list[AttendanceSession]] = defaultdict(list)
    for s in sessions:
        per_user[s.user_id].append(s)
    cols = [("department", "Department"), ("code", "Code"), ("employees", "Employees"), ("expected_days", "Expected person-days"), ("present_days", "Present person-days"), ("attendance_rate", "Attendance rate %"), ("late_days", "Late days"), ("worked", "Worked (h:mm)"), ("worked_minutes", "Worked (min)"), ("overtime_minutes", "Overtime (min)"), ("missing_clock_outs", "Missing clock-outs")]
    r = Report("department", f, cols)
    for dep_id, members in sorted(groups.items(), key=lambda kv: (deps[kv[0]].name if kv[0] in deps else "zzz")):
        dep = deps.get(dep_id)
        expected = 0
        present = 0
        late = worked = overtime = missing = 0
        for u in members:
            created = timeutil.local_date(u.created_at) if u.created_at else f.date_from
            expected += sum(1 for d in wdays if d >= created)
            ss = per_user.get(u.id, [])
            present += len({s.work_date for s in ss})
            late += sum(1 for s in ss if s.is_late)
            worked += sum(s.worked_minutes for s in ss)
            overtime += sum(s.overtime_minutes for s in ss)
            missing += sum(1 for s in ss if s.status == "missing_clock_out")
        r.rows.append({"department_id": str(dep_id) if dep_id else None, "department": dep.name if dep else "Unassigned", "code": dep.code if dep else "", "employees": len(members), "expected_days": expected, "present_days": present, "attendance_rate": round(100 * present / expected, 1) if expected else 0.0, "late_days": late, "worked": _hm(worked), "worked_minutes": worked, "overtime_minutes": overtime, "missing_clock_outs": missing})
    r.summary = {"departments": len(r.rows), "employees": len(users), "worked_minutes": sum(x["worked_minutes"] for x in r.rows), "present_days": sum(x["present_days"] for x in r.rows), "expected_days": sum(x["expected_days"] for x in r.rows)}
    return r


def _late(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    sessions = [s for s in _sessions(db, f, [u.id for u in users]) if s.is_late]
    cols = [("work_date", "Date"), *_emp_cols(), ("clock_in", "Clock in"), ("late_minutes", "Late (min)"), ("manual", "Manual"), ("corrected", "Corrected")]
    r = Report("late-arrivals", f, cols)
    for s in sessions:
        r.rows.append({"work_date": s.work_date.isoformat(), **_emp_row(s.user), "clock_in": timeutil.local_time_str(s.clock_in_at), "late_minutes": s.late_minutes, "manual": "yes" if s.is_manual else "no", "corrected": "yes" if s.corrected else "no", "session_id": str(s.id)})
    r.summary = {"late_arrivals": len(sessions), "employees": len({s.user_id for s in sessions}), "late_minutes": sum(s.late_minutes for s in sessions)}
    return r


def _absences(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f, include_inactive=False)
    sessions = _sessions(db, f, [u.id for u in users])
    today = timeutil.today_local()
    wdays = [d for d in _working_days(db, f) if d < today]
    present: dict[uuid.UUID, set[date]] = defaultdict(set)
    for s in sessions:
        present[s.user_id].add(s.work_date)
    cols = [("work_date", "Date"), *_emp_cols(), ("weekday", "Weekday")]
    r = Report("absences", f, cols)
    for u in users:
        created = timeutil.local_date(u.created_at) if u.created_at else f.date_from
        for d in wdays:
            if d >= created and d not in present[u.id]:
                r.rows.append({"work_date": d.isoformat(), **_emp_row(u), "weekday": d.strftime("%A")})
    r.rows.sort(key=lambda x: (x["work_date"], x["employee"]))
    r.summary = {"absences": len(r.rows), "employees": len({x["employee_id"] for x in r.rows}), "working_days": len(wdays)}
    return r


def _missing(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    f2 = ReportFilters(f.date_from, f.date_to, f.employee_id, f.department_id, None)
    sessions = [s for s in _sessions(db, f2, [u.id for u in users]) if s.status == "missing_clock_out" or (s.status == "open" and s.work_date < timeutil.today_local())]
    cols = [("work_date", "Date"), *_emp_cols(), ("clock_in", "Clock in"), ("status", "Status"), ("flagged_at", "Flagged at")]
    r = Report("missing-clock-outs", f, cols)
    for s in sessions:
        r.rows.append({"work_date": s.work_date.isoformat(), **_emp_row(s.user), "clock_in": timeutil.local_time_str(s.clock_in_at), "status": s.status, "flagged_at": timeutil.utc_iso(s.flagged_at), "session_id": str(s.id)})
    r.summary = {"missing_clock_outs": len(sessions), "employees": len({s.user_id for s in sessions})}
    return r


def _manual(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    ids = [u.id for u in users]
    cols = [("created_at", "Submitted"), *_emp_cols(), ("type", "Type"), ("work_date", "Work date"), ("requested_clock_in", "Requested in"), ("requested_clock_out", "Requested out"), ("reason", "Reason"), ("status", "Status"), ("reviewed_at", "Reviewed"), ("review_note", "Review note")]
    r = Report("manual-attendance", f, cols)
    if ids:
        stmt = select(ManualRequest).options(selectinload(ManualRequest.user).selectinload(User.department)).where(ManualRequest.user_id.in_(ids), ManualRequest.created_at >= timeutil.local_day_bounds_utc(f.date_from)[0], ManualRequest.created_at < timeutil.local_day_bounds_utc(f.date_to)[1])
        if f.status:
            stmt = stmt.where(ManualRequest.status == f.status)
        for q in db.execute(stmt.order_by(ManualRequest.created_at)).scalars():
            r.rows.append({"created_at": timeutil.local_iso(q.created_at), **_emp_row(q.user), "type": q.type, "work_date": q.work_date.isoformat() if q.work_date else "", "requested_clock_in": timeutil.local_time_str(q.requested_clock_in) or "", "requested_clock_out": timeutil.local_time_str(q.requested_clock_out) or "", "reason": q.reason, "status": q.status, "reviewed_at": timeutil.local_iso(q.reviewed_at) or "", "review_note": q.review_note or "", "request_id": str(q.id)})
    by_status: dict[str, int] = defaultdict(int)
    for x in r.rows:
        by_status[x["status"]] += 1
    manual_sessions = [s for s in _sessions(db, ReportFilters(f.date_from, f.date_to, f.employee_id, f.department_id, None), ids) if s.is_manual or s.corrected]
    r.summary = {"requests": len(r.rows), "by_status": dict(by_status), "manual_or_corrected_sessions": len(manual_sessions)}
    return r


def _pending(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    ids = [u.id for u in users]
    cols = [("received_at", "Detected"), *_emp_cols(), ("event_type", "Type"), ("source", "Source"), ("status", "Status"), ("score", "Score"), ("review_reason", "Reason"), ("expires_at", "Expires"), ("distance_m", "Distance (m)")]
    r = Report("pending-confirmations", f, cols)
    if ids:
        statuses = [f.status] if f.status else ["pending", "under_review", "expired"]
        stmt = select(AttendanceEvent).options(selectinload(AttendanceEvent.user).selectinload(User.department)).where(AttendanceEvent.user_id.in_(ids), AttendanceEvent.status.in_(statuses), AttendanceEvent.received_at >= timeutil.local_day_bounds_utc(f.date_from)[0], AttendanceEvent.received_at < timeutil.local_day_bounds_utc(f.date_to)[1])
        for e in db.execute(stmt.order_by(AttendanceEvent.received_at)).scalars():
            r.rows.append({"received_at": timeutil.local_iso(e.received_at), **_emp_row(e.user), "event_type": e.event_type, "source": e.source, "status": e.status, "score": round(e.verification_score, 2), "review_reason": e.review_reason or "", "expires_at": timeutil.local_iso(e.expires_at) or "", "distance_m": round(e.distance_from_workplace_m) if e.distance_from_workplace_m is not None else "", "event_id": str(e.id)})
    by_status: dict[str, int] = defaultdict(int)
    for x in r.rows:
        by_status[x["status"]] += 1
    r.summary = {"events": len(r.rows), "by_status": dict(by_status)}
    return r


def _working_hours(db: Session, f: ReportFilters) -> Report:
    users = _users(db, f)
    sessions = _sessions(db, f, [u.id for u in users])
    policy = get_policy(db)
    cols = [("work_date", "Date"), *_emp_cols(), ("clock_in", "Clock in"), ("clock_out", "Clock out"), ("gross_minutes", "Gross (min)"), ("break_minutes", "Break (min)"), ("worked_minutes", "Worked (min)"), ("worked", "Worked (h:mm)"), ("standard_minutes", "Standard (min)"), ("difference_minutes", "Difference (min)"), ("overtime_minutes", "Overtime (min)")]
    r = Report("working-hours", f, cols)
    for s in sessions:
        gross = timeutil.minutes_between(s.clock_in_at, s.clock_out_at) if s.clock_out_at else 0
        r.rows.append({"work_date": s.work_date.isoformat(), **_emp_row(s.user), "clock_in": timeutil.local_time_str(s.clock_in_at), "clock_out": timeutil.local_time_str(s.clock_out_at) or "", "gross_minutes": gross, "break_minutes": s.break_minutes, "worked_minutes": s.worked_minutes, "worked": _hm(s.worked_minutes), "standard_minutes": policy.standard_day_minutes, "difference_minutes": s.worked_minutes - policy.standard_day_minutes if s.clock_out_at else 0, "overtime_minutes": s.overtime_minutes, "session_id": str(s.id)})
    total = sum(x["worked_minutes"] for x in r.rows)
    r.summary = {"sessions": len(r.rows), "worked_minutes": total, "worked": _hm(total), "overtime_minutes": sum(x["overtime_minutes"] for x in r.rows), "standard_day_minutes": policy.standard_day_minutes}
    return r


_BUILDERS = {
    "daily": _daily,
    "weekly": _weekly,
    "monthly": _monthly,
    "employee": _employee,
    "department": _department,
    "late-arrivals": _late,
    "absences": _absences,
    "missing-clock-outs": _missing,
    "manual-attendance": _manual,
    "pending-confirmations": _pending,
    "working-hours": _working_hours,
}


def default_range(report: str, date_from: date | None, date_to: date | None) -> tuple[date, date]:
    today = timeutil.today_local()
    if date_to is None:
        date_to = today
    if date_from is None:
        if report in ("daily", "pending-confirmations"):
            date_from = date_to
        elif report == "weekly":
            date_from = timeutil.week_start(date_to)
        elif report == "monthly":
            date_from = timeutil.month_start(date_to)
        else:
            date_from = date_to - timedelta(days=29)
    if date_from > date_to:
        raise ApiError(422, "validation_error", "from must be on or before to.", {"fields": {"from": "after_to"}})
    if (date_to - date_from).days > 400:
        raise ApiError(422, "validation_error", "Report range may not exceed 400 days.", {"fields": {"from": "range_too_large"}})
    return date_from, date_to


def build(db: Session, report: str, f: ReportFilters) -> Report:
    if report not in _BUILDERS:
        raise ApiError(404, "not_found", f"Unknown report '{report}'.")
    return _BUILDERS[report](db, f)


# --------------------------------------------------------------------------------------
# renderers
# --------------------------------------------------------------------------------------


def render_csv(r: Report) -> bytes:
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow([label for _, label in r.columns])
    for row in r.rows:
        w.writerow([_csv_safe(row.get(k, "")) for k, _ in r.columns])
    return buf.getvalue().encode("utf-8-sig")


def _csv_safe(value: Any) -> Any:
    # Neutralise spreadsheet formula injection.
    if isinstance(value, str) and value[:1] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + value
    return value


def render_xlsx(r: Report) -> bytes:
    from openpyxl import Workbook
    from openpyxl.styles import Font
    from openpyxl.utils import get_column_letter

    wb = Workbook()
    ws = wb.active
    ws.title = r.name[:31]
    ws.append([label for _, label in r.columns])
    for c in ws[1]:
        c.font = Font(bold=True)
    for row in r.rows:
        ws.append([_xlsx_safe(row.get(k, "")) for k, _ in r.columns])
    for i, (k, _) in enumerate(r.columns, start=1):
        width = max([len(str(_))] + [len(str(x.get(k, ""))) for x in r.rows[:500]]) + 2
        ws.column_dimensions[get_column_letter(i)].width = min(60, max(8, width))
    ws.freeze_panes = "A2"
    meta = wb.create_sheet("Summary")
    meta.append(["Report", r.name])
    meta.append(["From", r.filters.date_from.isoformat()])
    meta.append(["To", r.filters.date_to.isoformat()])
    meta.append(["Generated", timeutil.utc_iso(timeutil.now_utc())])
    for k, v in r.summary.items():
        meta.append([k, str(v) if isinstance(v, (dict, list)) else v])
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def _xlsx_safe(value: Any) -> Any:
    if isinstance(value, str) and value[:1] in ("=", "+", "-", "@"):
        return "'" + value
    return value


def render_pdf(r: Report) -> bytes:
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.lib.styles import getSampleStyleSheet
    from reportlab.lib.units import mm
    from reportlab.platypus import (
        Paragraph,
        SimpleDocTemplate,
        Spacer,
        Table,
        TableStyle,
    )

    out = io.BytesIO()
    doc = SimpleDocTemplate(out, pagesize=landscape(A4), leftMargin=12 * mm, rightMargin=12 * mm, topMargin=12 * mm, bottomMargin=12 * mm, title=f"Meridian Attendance — {r.name}")
    styles = getSampleStyleSheet()
    story = [
        Paragraph(f"Meridian Group — Attendance report: {r.name}", styles["Title"]),
        Paragraph(f"Period {r.filters.date_from.isoformat()} to {r.filters.date_to.isoformat()} · generated {timeutil.local_iso(timeutil.now_utc())} · {len(r.rows)} rows", styles["Normal"]),
        Spacer(1, 6 * mm),
    ]
    cell = styles["BodyText"]
    cell.fontSize = 7
    cell.leading = 9
    data = [[Paragraph(str(label), cell) for _, label in r.columns]]
    for row in r.rows[:5000]:
        data.append([Paragraph(str(row.get(k, "")), cell) for k, _ in r.columns])
    if len(r.rows) > 5000:
        story.append(Paragraph(f"Showing the first 5000 of {len(r.rows)} rows; use CSV/XLSX for the full data.", styles["Italic"]))
    width = landscape(A4)[0] - 24 * mm
    table = Table(data, repeatRows=1, colWidths=[width / max(1, len(r.columns))] * len(r.columns))
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1f3a5f")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("GRID", (0, 0), (-1, -1), 0.25, colors.grey),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f3f6fa")]),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ]
        )
    )
    story.append(table)
    story.append(Spacer(1, 6 * mm))
    story.append(Paragraph("Summary: " + ", ".join(f"{k} = {v}" for k, v in r.summary.items()), styles["Normal"]))
    doc.build(story)
    return out.getvalue()


def render(r: Report, fmt: str) -> tuple[bytes, str, str]:
    """Returns (body, media_type, filename)."""
    stamp = f"{r.filters.date_from.isoformat()}_{r.filters.date_to.isoformat()}"
    if fmt == "csv":
        return render_csv(r), "text/csv; charset=utf-8", f"{r.name}_{stamp}.csv"
    if fmt == "xlsx":
        return render_xlsx(r), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", f"{r.name}_{stamp}.xlsx"
    if fmt == "pdf":
        return render_pdf(r), "application/pdf", f"{r.name}_{stamp}.pdf"
    raise ApiError(422, "validation_error", "format must be json, csv, xlsx or pdf.", {"fields": {"format": "invalid"}})
