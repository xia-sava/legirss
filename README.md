# legirss

[![CI](https://github.com/xia-sava/legirss/actions/workflows/ci.yml/badge.svg)](https://github.com/xia-sava/legirss/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Windows 用の RSS リーダー。たくさんのフィードの新着を、キーボードで手早く読み進めるために作った。
小さな文字と詰めた行で、1 画面に多くの記事を並べる。

Kotlin Multiplatform と Compose Multiplatform で書いている。動かせるのはデスクトップ（Windows）だけ。

> [!NOTE]
> このリポジトリのコードはほぼ全て [Claude Code](https://claude.com/claude-code) が書いている。
> 設計判断と実機での動作確認は人間が行っているが、実装の一行一行まで人間が検証したものではない。

## 特徴

- **高密度の 3 ペイン**。ツリー、記事の一覧、本文を 1 画面に並べる
- **Space だけで読み進められる**。押すたびに本文を送り、読み終えたら次の未読へ移る
- **タスクトレイに常駐する**。定期的に更新し、未読があるかどうかをアイコンの色で示す
- **RSS 0.9x / 1.0 / 2.0 と Atom 0.3 / 1.0** を読む。ページの URL からフィードを探して登録できる
- **OPML の読み込みと書き出し**ができる
- **アプリ内から自己更新**できる

## インストール

[Releases](https://github.com/xia-sava/legirss/releases) の `legirss.msi` を実行する。
利用者ごとのインストールなので、管理者の権限は要らない。

新しい版が出ると、ステータスバーの右端に更新ボタンが表示される。押すと、取得して入れ替え、起動し直す。

## 主なキー操作

操作はメニューバーにまとめてあり、キーは各項目のショートカットとして表示される。

| キー | 動作 |
|---|---|
| Space | 本文を 1 画面ぶん送る。末尾まで読んでいたら、一覧で一番下にある未読へ移る |
| Shift+Space | 本文を 1 画面ぶん戻す |
| Ctrl+Space | 一覧で 1 つ上の記事を選ぶ（↑ と同じ） |
| ↑ ↓ | 記事を選ぶ。選んだ記事は既読になる |
| Enter | 記事をブラウザで開く |
| ← → | 未読と既読を切り替える |
| Delete | 記事を削除する |
| Shift+↑↓ / Ctrl+A | 複数の記事に印を付ける。状態の変更や削除をまとめて行える |
| Ctrl+L | 記事の URL をコピーする |
| F2 / Alt+F2 | 全てを更新する / 選んだノード以下を更新する。更新中に押すと中止する |
| F3 | チャンネルを追加する |
| F5 | 一覧を並べ直す |
| F6 / Shift+F6 | ツリー、一覧、本文の間でフォーカスを移す |
| Ctrl+K | 一覧の絞り込み（全て・未読・既読）を切り替える |
| Esc | ウィンドウをタスクトレイへしまう |

名前の変更、削除、購読の中止と再開、グループごとの OPML の書き出しは、ツリーの右クリックメニューから行う。
ツリーのノードは、ドラッグで移動できる。

## データ

`%APPDATA%\legirss` に置く。

| ファイル | 中身 |
|---|---|
| `legirss.db` | 購読しているチャンネル、記事、設定（SQLite） |
| `backup\legirss-日付.db` | 1 日に 1 つ作るバックアップ。新しい方から 7 個を残す |
| `legirss.0.log` | 失敗の記録 |

既読の記事は、チャンネルごとに新しい方から 100 件、かつ 60 日以内のものを残す。数と日数は設定で変えられる。
未読の記事は、数と日数に関わらず残す。

## ビルド

JDK 25 が要る。

```bash
./gradlew :desktopApp:run          # 開発用に起動する。データは desktopApp/build/dev-data に置く
./gradlew check                    # テストを走らせる
./gradlew :desktopApp:packageMsi   # インストーラを作る
```

## ライセンス

[MIT](LICENSE)
