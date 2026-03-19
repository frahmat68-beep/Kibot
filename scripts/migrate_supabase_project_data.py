#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass

import psycopg
from psycopg import sql
from psycopg.types.json import Json, Jsonb


TABLES_TO_COPY = [
    "devices",
    "api_credentials_encrypted",
    "orders",
    "fills",
    "positions",
    "daily_equity",
    "risk_events",
    "logs",
    "strategy_metrics",
    "market_snapshots",
    "cleanup_runs",
    "daily_trade_summary",
    "parameter_versions",
    "mode_metrics",
    "weekly_learning_reviews",
    "no_trade_reviews",
]

VOLATILE_TABLES_SKIPPED = [
    "engine_heartbeats",
    "command_queue",
    "execution_actions",
]


@dataclass
class MigrationContext:
    source_owner_id: str
    target_owner_id: str
    target_owner_email: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Copy KiBot public data from one Supabase project to another."
    )
    parser.add_argument("--source-db-url", required=True)
    parser.add_argument("--target-db-url", required=True)
    parser.add_argument("--source-owner-email", required=True)
    parser.add_argument("--target-owner-email", required=True)
    parser.add_argument("--target-url")
    parser.add_argument("--target-service-role")
    parser.add_argument("--target-owner-password")
    parser.add_argument("--ensure-target-owner", action="store_true")
    return parser.parse_args()


def admin_create_owner(
    target_url: str,
    service_role: str,
    email: str,
    password: str,
) -> None:
    endpoint = target_url.rstrip("/") + "/auth/v1/admin/users"
    payload = json.dumps(
        {
            "email": email,
            "password": password,
            "email_confirm": True,
        }
    ).encode()
    request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={
            "apikey": service_role,
            "Authorization": f"Bearer {service_role}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20):
            return
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        if exc.code in {400, 422} and "already" in body.lower():
            return
        raise RuntimeError(f"Owner create failed: HTTP {exc.code} {body}") from exc


def fetch_owner_ids(
    source_conn: psycopg.Connection,
    target_conn: psycopg.Connection,
    source_email: str,
    target_email: str,
) -> MigrationContext:
    with source_conn.cursor() as cur:
        cur.execute(
            "select user_id::text from public.profiles where email = %s limit 1",
            (source_email,),
        )
        row = cur.fetchone()
        if not row:
            raise RuntimeError(f"Source owner not found for email: {source_email}")
        source_owner_id = row[0]

    with target_conn.cursor() as cur:
        cur.execute(
            "select id::text from auth.users where email = %s limit 1",
            (target_email,),
        )
        row = cur.fetchone()
        if not row:
            raise RuntimeError(f"Target owner not found for email: {target_email}")
        target_owner_id = row[0]

        cur.execute(
            """
            insert into public.profiles (user_id, email, display_name)
            values (%s::uuid, %s, split_part(%s, '@', 1))
            on conflict (user_id) do update set email = excluded.email
            """,
            (target_owner_id, target_email, target_email),
        )
        cur.execute(
            """
            insert into public.bots (bot_id, user_id, display_name)
            values ('main', %s::uuid, 'KiBot Main')
            on conflict (bot_id) do update set user_id = excluded.user_id
            """,
            (target_owner_id,),
        )
        cur.execute(
            """
            insert into public.bot_state (bot_id)
            values ('main')
            on conflict (bot_id) do nothing
            """
        )
        cur.execute(
            """
            insert into public.engine_leases (bot_id)
            values ('main')
            on conflict (bot_id) do nothing
            """
        )

    return MigrationContext(
        source_owner_id=source_owner_id,
        target_owner_id=target_owner_id,
        target_owner_email=target_email,
    )


def table_columns(conn: psycopg.Connection, table: str) -> list[str]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = %s
            order by ordinal_position
            """,
            (table,),
        )
        return [row[0] for row in cur.fetchall()]


def table_column_types(conn: psycopg.Connection, table: str) -> dict[str, str]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select column_name, udt_name
            from information_schema.columns
            where table_schema = 'public' and table_name = %s
            """,
            (table,),
        )
        return {row[0]: row[1] for row in cur.fetchall()}


def read_rows(
    conn: psycopg.Connection,
    table: str,
    columns: list[str],
) -> list[dict]:
    query = sql.SQL("select {fields} from {table}").format(
        fields=sql.SQL(", ").join(sql.Identifier(column) for column in columns),
        table=sql.Identifier("public", table),
    )
    with conn.cursor() as cur:
        cur.execute(query)
        rows = cur.fetchall()
    return [dict(zip(columns, row)) for row in rows]


def transform_row(table: str, row: dict, context: MigrationContext) -> dict:
    transformed = dict(row)
    user_id = transformed.get("user_id")
    if user_id is not None and str(user_id) == context.source_owner_id:
        transformed["user_id"] = context.target_owner_id
    return transformed


def adapt_value(value, udt_name: str):
    if value is None:
        return None
    if udt_name == "jsonb":
        return Jsonb(value)
    if udt_name == "json":
        return Json(value)
    return value


def copy_table(
    source_conn: psycopg.Connection,
    target_conn: psycopg.Connection,
    table: str,
    context: MigrationContext,
) -> int:
    columns = table_columns(source_conn, table)
    column_types = table_column_types(source_conn, table)
    rows = [transform_row(table, row, context) for row in read_rows(source_conn, table, columns)]

    with target_conn.cursor() as cur:
        cur.execute(
            sql.SQL("truncate table {table} restart identity cascade").format(
                table=sql.Identifier("public", table)
            )
        )

        if rows:
            values = [
                [adapt_value(row[column], column_types[column]) for column in columns]
                for row in rows
            ]
            placeholders = sql.SQL(", ").join(sql.Placeholder() for _ in columns)
            insert_query = sql.SQL(
                "insert into {table} ({fields}) values ({values})"
            ).format(
                table=sql.Identifier("public", table),
                fields=sql.SQL(", ").join(sql.Identifier(column) for column in columns),
                values=placeholders,
            )
            cur.executemany(insert_query, values)

    reset_sequences(target_conn, table)
    return len(rows)


def reset_sequences(conn: psycopg.Connection, table: str) -> None:
    columns = table_columns(conn, table)
    with conn.cursor() as cur:
        for column in columns:
            cur.execute(
                "select pg_get_serial_sequence(%s, %s)",
                (f"public.{table}", column),
            )
            row = cur.fetchone()
            if not row or not row[0]:
                continue
            sequence_name = row[0]
            cur.execute(
                sql.SQL(
                    """
                    select setval(
                        %s::regclass,
                        coalesce((select max({column}) from {table}), 1),
                        (select count(*) > 0 from {table})
                    )
                    """
                ).format(
                    column=sql.Identifier(column),
                    table=sql.Identifier("public", table),
                ),
                (sequence_name,),
            )


def migrate_bot_state(
    source_conn: psycopg.Connection,
    target_conn: psycopg.Connection,
) -> None:
    columns = table_columns(source_conn, "bot_state")
    column_types = table_column_types(source_conn, "bot_state")
    rows = read_rows(source_conn, "bot_state", columns)
    if not rows:
        return
    row = rows[0]
    row["desired_state"] = "OFF"
    row["effective_state"] = "STOPPED"
    row["active_device_id"] = None
    row["standby_device_id"] = None
    row["sync_health"] = "DEGRADED"
    row["safe_mode_reason"] = "MIGRATED_TO_NEW_PROJECT"
    row["current_pair"] = None
    row["last_heartbeat_at"] = None

    with target_conn.cursor() as cur:
        assignments = sql.SQL(", ").join(
            sql.SQL("{} = {}").format(sql.Identifier(column), sql.Placeholder())
            for column in columns
            if column != "bot_id"
        )
        values = [
            adapt_value(row[column], column_types[column])
            for column in columns
            if column != "bot_id"
        ]
        values.append(row["bot_id"])
        cur.execute(
            sql.SQL("update {table} set {assignments} where bot_id = %s").format(
                table=sql.Identifier("public", "bot_state"),
                assignments=assignments,
            ),
            values,
        )


def migrate_engine_leases(
    source_conn: psycopg.Connection,
    target_conn: psycopg.Connection,
) -> None:
    columns = table_columns(source_conn, "engine_leases")
    column_types = table_column_types(source_conn, "engine_leases")
    rows = read_rows(source_conn, "engine_leases", columns)
    if not rows:
        return
    row = rows[0]
    row["holder_device_id"] = None
    row["state"] = "RELEASED"
    row["expires_at"] = None
    row["last_heartbeat_at"] = None
    row["conflict_detected"] = False

    with target_conn.cursor() as cur:
        assignments = sql.SQL(", ").join(
            sql.SQL("{} = {}").format(sql.Identifier(column), sql.Placeholder())
            for column in columns
            if column != "bot_id"
        )
        values = [
            adapt_value(row[column], column_types[column])
            for column in columns
            if column != "bot_id"
        ]
        values.append(row["bot_id"])
        cur.execute(
            sql.SQL("update {table} set {assignments} where bot_id = %s").format(
                table=sql.Identifier("public", "engine_leases"),
                assignments=assignments,
            ),
            values,
        )


def print_summary(copied_counts: dict[str, int]) -> None:
    summary = {
        "copied_tables": copied_counts,
        "skipped_volatile": VOLATILE_TABLES_SKIPPED,
    }
    print(json.dumps(summary, indent=2, sort_keys=True))


def main() -> int:
    args = parse_args()

    if args.ensure_target_owner:
        required = [args.target_url, args.target_service_role, args.target_owner_password]
        if not all(required):
            raise SystemExit(
                "--ensure-target-owner requires --target-url, --target-service-role, and --target-owner-password"
            )
        admin_create_owner(
            target_url=args.target_url,
            service_role=args.target_service_role,
            email=args.target_owner_email,
            password=args.target_owner_password,
        )

    copied_counts: dict[str, int] = {}
    with psycopg.connect(
        args.source_db_url,
        autocommit=True,
        connect_timeout=20,
        prepare_threshold=None,
    ) as source_conn:
        with psycopg.connect(
            args.target_db_url,
            autocommit=True,
            connect_timeout=20,
            prepare_threshold=None,
        ) as target_conn:
            context = fetch_owner_ids(
                source_conn=source_conn,
                target_conn=target_conn,
                source_email=args.source_owner_email,
                target_email=args.target_owner_email,
            )

            for table in TABLES_TO_COPY:
                copied_counts[table] = copy_table(
                    source_conn=source_conn,
                    target_conn=target_conn,
                    table=table,
                    context=context,
                )

            # Re-assert core rows last so the migrated project always starts from a clean,
            # released state even if intermediate truncates touch dependent rows.
            context = fetch_owner_ids(
                source_conn=source_conn,
                target_conn=target_conn,
                source_email=args.source_owner_email,
                target_email=args.target_owner_email,
            )
            migrate_bot_state(source_conn, target_conn)
            migrate_engine_leases(source_conn, target_conn)

    print_summary(copied_counts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
