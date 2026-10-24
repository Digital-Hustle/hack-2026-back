#!/usr/bin/env python3
"""Canonical ForecastSnapshot SHA-256 profile; no third-party dependencies.

Usage: python3 scripts/fingerprint.py path.json [--write]
Supports calculate, calibration-origin, and backtest payloads. Never drops duplicate
identities; Forecast validates them. --write updates only the supplied local file.
"""
import argparse
import copy
import datetime as dt
import hashlib
import json
import pathlib
import re
import uuid
from decimal import Decimal

SORTS = {
    "dailyHistory": lambda row: (row["date"],),
    "actualDays": lambda row: (row["date"],),
    "payments": lambda row: (row["date"], row["paymentId"].lower()),
    "actualPayments": lambda row: (row["date"], row["paymentId"].lower()),
    "knownPlans": lambda row: (row["dueDate"], row["id"].lower()),
    "recurringPatterns": lambda row: (row["id"].lower(),),
    "completeRanges": lambda row: (row["fromDate"], row["toDate"], row["id"].lower()),
    "calibrationOrigins": lambda row: (row["input"]["asOfDate"],),
}
IDS = {"id", "companyId", "accountId", "paymentId", "recurringPatternId"}
INSTANTS = {"createdAt", "updatedAt", "knownAt", "knowledgeCutoff"}
MONEY = {"inflow", "outflow", "balanceEod", "amount", "startBalance", "bufferAmount", "settledAmount", "remainingAmount"}


def canonical(value, field=""):
    if isinstance(value, dict):
        snapshot = "dailyHistory" in value and "asOfDate" in value and "settings" in value
        return {key: canonical(item, key) for key, item in sorted(value.items())
                if not (snapshot and key in ("snapshotId", "fingerprint"))}
    if isinstance(value, list):
        rows = sorted(value, key=SORTS[field]) if field in SORTS else value
        return [canonical(row) for row in rows]
    if isinstance(value, str):
        if field in IDS:
            return str(uuid.UUID(value))
        if field in INSTANTS:
            match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})", value)
            if not match:
                raise ValueError("Invalid ISO-8601 instant")
            nanos = int((match.group(2) or "").ljust(9, "0"))
            instant = dt.datetime.fromisoformat(match.group(1) + match.group(3).replace("Z", "+00:00")).astimezone(dt.timezone.utc)
            result = instant.strftime("%Y-%m-%dT%H:%M:%S")
            if nanos:
                fraction = f"{nanos:09d}"
                result += "." + (fraction[:3] if nanos % 1000000 == 0 else fraction[:6] if nanos % 1000 == 0 else fraction)
            return result + "Z"
        if field in MONEY:
            amount = Decimal(value)
            if amount.as_tuple().exponent != -2:
                raise ValueError("Money must already have exactly two decimals")
            return "0.00" if amount.is_zero() else format(amount, ".2f")
    return value


def fingerprint(snapshot):
    wire = json.dumps(canonical(snapshot), ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(wire.encode("utf-8")).hexdigest()


def stamp(value):
    if isinstance(value, dict):
        for item in list(value.values()):
            stamp(item)
        if "dailyHistory" in value and "asOfDate" in value and "settings" in value:
            value["fingerprint"] = fingerprint(value)
    elif isinstance(value, list):
        for item in value:
            stamp(item)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=pathlib.Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    original = json.loads(args.file.read_text(encoding="utf-8"))
    data = copy.deepcopy(original)
    stamp(data)
    snapshots = [data] if "dailyHistory" in data else [data["input"]] if "input" in data else data.get("originSnapshots", [])
    if not snapshots:
        parser.error("No ForecastSnapshot in this file")
    for snapshot in snapshots:
        print(snapshot["asOfDate"], snapshot["fingerprint"])
    if args.write:
        args.file.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
