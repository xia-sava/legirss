"""legirss のデータベースの状態を、読み取り専用で開いて表示する。

アプリが動いている間でも使える。データベースには何も書かない。

使い方:
    python inspect_db.py <legirss.db のパス>
"""

import argparse
import sqlite3
from pathlib import Path

ARTICLE_STATE_NAMES = {0: "未読", 1: "既読", 2: "保護"}
ERROR_KIND_NAMES = {1: "応答なし", 2: "HTTP エラー", 3: "フィードとして読めない"}


def scalar(connection: sqlite3.Connection, sql: str) -> int:
    return connection.execute(sql).fetchone()[0]


def main() -> None:
    parser = argparse.ArgumentParser(description="legirss のデータベースの状態を表示する")
    parser.add_argument("database", type=Path, help="legirss.db のパス")
    arguments = parser.parse_args()

    connection = sqlite3.connect(f"file:{arguments.database.as_posix()}?mode=ro", uri=True)

    print(f"スキーマの版: {scalar(connection, 'PRAGMA user_version')}")
    print(f"グループ: {scalar(connection, 'SELECT COUNT(*) FROM node WHERE kind = 0')}")
    print(
        f"チャンネル: {scalar(connection, 'SELECT COUNT(*) FROM channel')}"
        f"（購読中止 {scalar(connection, 'SELECT COUNT(*) FROM channel WHERE paused = 1')}，"
        f"未取得 {scalar(connection, 'SELECT COUNT(*) FROM channel WHERE fetched_at IS NULL')}）"
    )

    print("記事:")
    for state, count in connection.execute("SELECT state, COUNT(*) FROM article GROUP BY state ORDER BY state"):
        print(f"  {ARTICLE_STATE_NAMES.get(state, state)}: {count}")
    print(f"消した記事の控え: {scalar(connection, 'SELECT COUNT(*) FROM seen')}")

    print("直近の更新が失敗しているチャンネル:")
    failures = connection.execute(
        "SELECT error_kind, COUNT(*) FROM channel WHERE error_kind <> 0 GROUP BY error_kind ORDER BY error_kind"
    ).fetchall()
    for kind, count in failures:
        print(f"  {ERROR_KIND_NAMES.get(kind, kind)}: {count}")
    if not failures:
        print("  なし")

    print("設定:")
    for key, value in connection.execute("SELECT key, value FROM setting ORDER BY key"):
        print(f"  {key} = {value}")


if __name__ == "__main__":
    main()
