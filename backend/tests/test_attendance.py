from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy import select

from app.core import timeutil
from app.models import AttendanceEvent, AttendanceSession
from app.services.scheduler import run_once
from tests.conftest import (
    OFFICE_LAT,
    OFFICE_LON,
    auth,
    confirm_body,
    event_body,
    make_workplace,
)

MONDAY = date(2026, 11, 16)


def submit(client, tokens, body):
    return client.post("/api/v1/attendance/events", json=body, headers=auth(tokens))


def confirm(client, tokens, event_id, at=None):
    return client.post(f"/api/v1/attendance/events/{event_id}/confirm", json=confirm_body(at), headers=auth(tokens))


def test_full_day_arrival_and_departure(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:02")
    r = submit(client, emp_tokens, event_body(workplace))
    assert r.status_code == 201
    ev = r.json()["event"]
    assert ev["status"] == "pending" and ev["verification"]["inside_geofence"] is True and ev["verification_score"] >= 0.6
    assert r.json()["today"]["status"] == "pending_arrival"
    r = confirm(client, emp_tokens, ev["id"])
    assert r.status_code == 200
    body = r.json()
    assert body["event"]["status"] == "confirmed"
    assert body["session"]["status"] == "open" and body["session"]["clock_in_local"].startswith("2026-11-16T08:02")
    assert body["session"]["is_late"] is False
    assert body["today"]["status"] == "clocked_in"

    clock.local(MONDAY, "17:05")
    r = submit(client, emp_tokens, event_body(workplace, "departure", lat=OFFICE_LAT + 0.01))
    assert r.status_code == 201 and r.json()["today"]["status"] == "pending_departure"
    r = confirm(client, emp_tokens, r.json()["event"]["id"])
    s = r.json()["session"]
    assert s["status"] == "closed"
    # 08:02 → 17:05 = 543 min gross, minus 60 min policy break = 483 worked, 3 overtime
    assert s["worked_minutes"] == 483 and s["break_minutes"] == 60 and s["overtime_minutes"] == 3
    assert r.json()["today"]["status"] == "clocked_out" and r.json()["today"]["worked_minutes_today"] == 483


def test_late_flag(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:40")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    s = confirm(client, emp_tokens, ev["id"]).json()["session"]
    assert s["is_late"] is True and s["late_minutes"] == 40


def test_idempotent_replay_returns_200(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    body = event_body(workplace)
    a = submit(client, emp_tokens, body)
    b = submit(client, emp_tokens, body)
    assert a.status_code == 201 and b.status_code == 200
    assert a.json()["event"]["id"] == b.json()["event"]["id"]
    c = confirm_body()
    r1 = client.post(f"/api/v1/attendance/events/{a.json()['event']['id']}/confirm", json=c, headers=auth(emp_tokens))
    r2 = client.post(f"/api/v1/attendance/events/{a.json()['event']['id']}/confirm", json=c, headers=auth(emp_tokens))
    assert r1.status_code == r2.status_code == 200 and r1.json()["session"]["id"] == r2.json()["session"]["id"]


def test_duplicate_window_returns_existing(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    a = submit(client, emp_tokens, event_body(workplace))
    clock.advance(minutes=3)
    b = submit(client, emp_tokens, event_body(workplace))
    assert b.status_code == 200 and b.json()["event"]["id"] == a.json()["event"]["id"]


def test_future_timestamp_rejected(client, emp_tokens, workplace, clock):
    now = clock.local(MONDAY, "08:00")
    r = submit(client, emp_tokens, event_body(workplace, detected_at=now + timedelta(minutes=10)))
    assert r.status_code == 201
    ev = r.json()["event"]
    assert ev["status"] == "rejected" and ev["review_reason"] == "timestamp_implausible"
    assert confirm(client, emp_tokens, ev["id"]).status_code == 400


def test_stale_offline_rejected(client, emp_tokens, workplace, clock):
    now = clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace, detected_at=now - timedelta(hours=80), offline=True)).json()["event"]
    assert ev["status"] == "rejected" and ev["review_reason"] == "stale_offline"


def test_online_clock_skew_goes_under_review_with_device_time(client, emp_tokens, workplace, clock):
    now = clock.local(MONDAY, "08:30")
    detected = now - timedelta(minutes=20)
    ev = submit(client, emp_tokens, event_body(workplace, detected_at=detected)).json()["event"]
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "under_review" and r["event"]["review_reason"] == "clock_skew"
    assert r["event"]["official_at"] == timeutil.utc_iso(detected)
    assert r["session"] is None


def test_outside_geofence_under_review_after_confirm(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace, lat=OFFICE_LAT + 0.05, lon=OFFICE_LON)).json()["event"]
    assert ev["status"] == "pending" and ev["verification"]["inside_geofence"] is False
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "under_review" and r["event"]["review_reason"] == "outside_geofence"


def test_wifi_required(client, emp_tokens, db, clock):
    wp = make_workplace(db, wifi_required=True)
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(wp)).json()["event"]
    assert confirm(client, emp_tokens, ev["id"]).json()["event"]["review_reason"] == "wifi_required"
    clock.local(MONDAY + timedelta(days=1), "08:00")
    ev = submit(client, emp_tokens, event_body(wp, wifi={"ssid": "Other", "bssid": "AA:BB:CC:DD:EE:FF"})).json()["event"]
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "confirmed" and r["event"]["wifi_verified"] is True


def test_unconfirmed_placeholder_workplace_needs_review(client, emp_tokens, db, clock):
    wp = make_workplace(db, confirmed=False)
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(wp)).json()["event"]
    assert ev["verification"]["inside_geofence"] is None
    assert confirm(client, emp_tokens, ev["id"]).json()["event"]["status"] == "under_review"


def test_arrival_while_open_is_409(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    confirm(client, emp_tokens, ev["id"])
    clock.local(MONDAY, "09:00")
    r = submit(client, emp_tokens, event_body(workplace))
    assert r.status_code == 409 and r.json()["error"]["code"] == "invalid_state"
    assert r.json()["error"]["details"]["session"]["status"] == "open"


def test_departure_without_session_under_review(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "17:00")
    ev = submit(client, emp_tokens, event_body(workplace, "departure", lat=OFFICE_LAT + 0.01)).json()["event"]
    assert ev["review_reason"] == "no_open_session"
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "under_review" and r["session"] is None


def test_dismiss(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    r = client.post(f"/api/v1/attendance/events/{ev['id']}/dismiss", json={"reason": "not_at_work"}, headers=auth(emp_tokens))
    assert r.status_code == 200 and r.json()["status"] == "dismissed"
    assert client.get("/api/v1/attendance/pending", headers=auth(emp_tokens)).json()["items"] == []
    assert confirm(client, emp_tokens, ev["id"]).status_code == 400


def test_expiry_via_scheduler(client, emp_tokens, workplace, clock, db):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    clock.local(MONDAY, "21:00")  # > 12 h pending_expiry_hours
    result = run_once()
    assert result["expired_events"] == 1
    db.expire_all()
    assert db.get(AttendanceEvent, ev["id"]).status == "expired"
    r = confirm(client, emp_tokens, ev["id"])
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_state"


def test_missing_clock_out_flag(client, emp_tokens, workplace, clock, db):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    confirm(client, emp_tokens, ev["id"])
    clock.local(MONDAY, "23:45")
    assert run_once()["missing_clock_outs"] == 1
    db.expire_all()
    assert db.execute(select(AttendanceSession)).scalar_one().status == "missing_clock_out"
    assert client.get("/api/v1/attendance/today", headers=auth(emp_tokens)).json()["status"] == "missing_clock_out"


def test_location_pseudonymisation(client, emp_tokens, workplace, clock, db):
    clock.local(MONDAY - timedelta(days=400), "08:00")
    submit(client, emp_tokens, event_body(workplace))
    clock.local(MONDAY, "08:00")
    assert run_once()["locations_pseudonymised"] == 1
    db.expire_all()
    e = db.execute(select(AttendanceEvent)).scalar_one()
    assert e.latitude is None and e.location_pseudonymised_at is not None


def test_sync_batch(client, emp_tokens, workplace, clock):
    now = clock.local(MONDAY, "12:00")
    arrival = event_body(workplace, detected_at=now - timedelta(hours=4), offline=True)
    items = [
        {"kind": "event", "payload": arrival},
        {"kind": "confirmation", "event_id": arrival["client_event_id"], "payload": confirm_body(now - timedelta(hours=4))},
        {"kind": "event", "payload": {"bad": "payload"}},
        {"kind": "dismissal", "event_id": "does-not-exist", "payload": {"reason": "other"}},
    ]
    r = client.post("/api/v1/attendance/sync", json={"items": items}, headers=auth(emp_tokens))
    assert r.status_code == 200
    res = r.json()["results"]
    assert [x["ok"] for x in res] == [True, True, False, False]
    assert res[0]["status"] == 201 and res[1]["event"]["status"] == "under_review"  # offline → device time → review
    assert res[2]["status"] == 422 and res[3]["status"] == 404
    # replay is idempotent
    again = client.post("/api/v1/attendance/sync", json={"items": items[:2]}, headers=auth(emp_tokens)).json()["results"]
    assert again[0]["status"] == 200 and again[0]["event"]["id"] == res[0]["event"]["id"]


def test_history_endpoints(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    sid = confirm(client, emp_tokens, ev["id"]).json()["session"]["id"]
    h = auth(emp_tokens)
    s = client.get("/api/v1/attendance/sessions?from=2026-11-01&to=2026-11-30", headers=h).json()
    assert s["total"] == 1 and s["items"][0]["id"] == sid
    d = client.get(f"/api/v1/attendance/sessions/{sid}", headers=h).json()
    assert d["events"][0]["id"] == ev["id"]
    e = client.get("/api/v1/attendance/events?status=confirmed", headers=h).json()
    assert e["total"] == 1


def test_admin_approve_and_reject_review_events(client, emp_tokens, workplace, clock, manager_tokens):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace, lat=OFFICE_LAT + 0.05)).json()["event"]
    confirm(client, emp_tokens, ev["id"])
    r = client.post(f"/api/v1/admin/attendance/events/{ev['id']}/approve", json={"note": "Was at client site"}, headers=auth(manager_tokens))
    assert r.status_code == 200 and r.json()["status"] == "confirmed" and r.json()["session_id"]
    clock.local(MONDAY, "17:00")
    dep = submit(client, emp_tokens, event_body(workplace, "departure", lat=OFFICE_LAT + 0.05)).json()["event"]
    r = client.post(f"/api/v1/admin/attendance/events/{dep['id']}/reject", json={"note": "Not plausible"}, headers=auth(manager_tokens))
    assert r.status_code == 200 and r.json()["status"] == "rejected"
    assert client.post(f"/api/v1/admin/attendance/events/{dep['id']}/approve", json={}, headers=auth(manager_tokens)).status_code == 400


# --- departures are evidenced by being OUTSIDE the geofence (regression: found by the live
#     Android-DTO contract round trip, where every genuine clock-out went to review) ---------
def _clock_in(client, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(workplace)).json()["event"]
    assert confirm(client, emp_tokens, ev["id"]).json()["session"]["status"] == "open"


def test_departure_outside_geofence_closes_session(client, emp_tokens, workplace, clock):
    _clock_in(client, emp_tokens, workplace, clock)
    clock.local(MONDAY, "17:05")
    ev = submit(client, emp_tokens, event_body(workplace, "departure", lat=OFFICE_LAT + 0.01)).json()["event"]
    assert ev["verification"]["inside_geofence"] is False
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "confirmed", r["event"]["verification"]
    assert r["session"]["status"] == "closed" and r["session"]["worked_minutes"] > 0


def test_geofence_departure_still_inside_goes_to_review(client, emp_tokens, workplace, clock):
    _clock_in(client, emp_tokens, workplace, clock)
    clock.local(MONDAY, "12:00")
    ev = submit(client, emp_tokens, event_body(workplace, "departure")).json()["event"]
    r = confirm(client, emp_tokens, ev["id"]).json()
    assert r["event"]["status"] == "under_review" and r["event"]["review_reason"] == "departure_still_inside"
    assert r["session"] is None  # never clocked out on contradictory evidence


def test_departure_not_blocked_by_wifi_required(client, emp_tokens, db, clock):
    wp = make_workplace(db, wifi_required=True)
    clock.local(MONDAY, "08:00")
    ev = submit(client, emp_tokens, event_body(wp, wifi={"ssid": "Office-Staff", "bssid": "aa:bb:cc:dd:ee:ff"})).json()["event"]
    assert confirm(client, emp_tokens, ev["id"]).json()["event"]["status"] == "confirmed"
    clock.local(MONDAY, "17:00")
    ev = submit(client, emp_tokens, event_body(wp, "departure", lat=OFFICE_LAT + 0.01)).json()["event"]
    assert confirm(client, emp_tokens, ev["id"]).json()["event"]["status"] == "confirmed"
