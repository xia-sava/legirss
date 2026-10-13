---
name: release
description: legirss の版を上げて配布する。バージョンタグを打って GitHub Actions に配布物を作らせる手順、打つ前の確認、打ち直しが効かないことなど。「バージョンを上げて」「リリースして」「配布して」と言われたときに使う。
---

# 版を上げる

`vMAJOR.MINOR.PATCH` の形のタグを push すると、GitHub Actions が配布物を作って公開する。
main への push ではテストしか走らず、配布物は出ない。

版数はタグ名から先頭の `v` を除いたもので、**人が決める**。インストーラの版数、アプリが自分の版として
持つ値、マニフェスト（`latest.json`）の `versionName` は、どれもこの値になる。

## 打つ前に確かめる

1. **作業ツリーがクリーンで、検証が通っていること。**

   ```
   git status --short
   ./gradlew check
   ```

2. **main が push 済みで、CI が緑であること。**
   タグだけ先に push してもコミット自体は飛ぶが、ブランチ参照が古いまま残って追いにくくなる。

   ```
   git status -sb
   gh run list --workflow ci.yml --limit 1
   ```

   main の push も外部公開操作なので、実行の直前にユーザーへ確認を取る。

3. **配布中の版を確認して、次の版を決める。**
   番号は人が数える。直しだけなら patch を、機能を足したなら minor を上げる。

   ```
   curl -fsSL https://github.com/xia-sava/legirss/releases/download/latest/latest.json
   ```

## 打つ

```
git tag v0.2.1
git push origin v0.2.1
```

**タグの push は外部公開操作。実行の直前に必ずユーザーへ確認を取る。**
依頼済みであっても、押す瞬間に改めて一声かける。

## 確認する

```
gh run list --workflow release.yml --limit 1
gh run watch <run の ID> --exit-status
```

`Release` ワークフローが走る（10 分ほど）。成功すると 2 つのリリースができている。

- **`v0.2.1`** … 版ごとの記録。配布物の実物が残るので、どの版で何を配ったかを後から辿れる
- **`latest`** … 更新の経路。インストール済みのアプリは、ここからマニフェストと配布物を取得する

公開されたものは、アプリと同じやり方で確かめられる。署名が合うことと、msi の SHA-256 が
マニフェストの値と一致することを見る。

```
base=https://github.com/xia-sava/legirss/releases/download/latest
curl -fsSL -o latest.json $base/latest.json
curl -fsSL -o latest.json.sig $base/latest.json.sig
curl -fsSL -o legirss.msi $base/legirss.msi
base64 -d latest.json.sig > latest.json.sig.der
openssl dgst -sha256 -verify <公開鍵の PEM> -signature latest.json.sig.der latest.json
sha256sum legirss.msi
```

インストール済みのアプリは、起動した直後と、そのあと 24 時間おきに配布中の版を確かめる。
公開のあとにアプリを起動し直すと、ツールバーの右端に更新ボタンが出る。

## 気をつけること

- **同じ版や古い版は公開できない。** 配布中の `latest.json` より版が新しいことを、ワークフローが
  最初に確かめる。ここで落ちたら、タグの打ち間違いを疑う。打ち直すのではなく、版を上げて出す。
- **版を下げる手段は無い。** インストーラは、版数が増えていることを置き換えの条件にしている。
  問題が出たら戻すのではなく、直した版を出す。
- **接尾辞つきのタグは通らない**（`v1.0.0-rc1` など）。インストーラの版数が major.minor.patch に
  限られるため、ワークフローが弾く。
- **`latest` の置き場所は動かさない。** インストール済みのアプリは、取得先を `latest` に固定して
  組み立てている（マニフェストが指す URL を信用しない設計）。動かすと、どのアプリも更新を
  見つけられなくなる。
- **署名の鍵を替えない。** マニフェストの署名は secrets の `LEGIRSS_SIGNING_KEY` で付け、アプリは
  埋め込んである公開鍵（`UpdateManifest.PUBLIC_KEY`）で確かめる。`release.yml` の
  `MANIFEST_PUBLIC_KEY` も同じ値でなければならない。対になっていない鍵で公開すると、
  インストール済みのアプリは更新を受け付けなくなる。ワークフローは公開の前に、付けた署名を
  この公開鍵で確かめる。

## 配布せずに手元で試したいとき

インストーラは手元で作れる。リリースには触れないので、ほかのアプリには何も届かない。

```
./gradlew :desktopApp:packageMsi -Plegirss.version=0.2.1
```

できたものは `desktopApp/build/compose/binaries/main/msi/` に置かれる。

版数は、次に公開する版より小さくしておく。同じ版や大きい版を入れてしまうと、あとから公開した版が
更新として届かない。入っている版より小さい版も入れられないので、配布中の版と次に公開する版の
間の数を使う。
