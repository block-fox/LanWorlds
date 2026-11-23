# LanWorlds

![LanWorlds](docs/lanworlds-cover.png)

[English](README.md) · [简体中文](README.zh-CN.md) · [Tiếng Việt](README.vi.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · **日本語**

LanWorlds は、Minecraft 標準の LAN ルーム一覧を拡張するクライアント側の Fabric MOD です。Minecraft **26.3、26.2、1.21.11、1.21.1** に対応しています。Fabric API を導入し、ゲームのバージョンに合う JAR を [GitHub Releases](https://github.com/block-fox/LanWorlds/releases) からダウンロードしてください。

## 主な機能

- 標準のマルチプレイ画面からルームに参加できます。ルームを右クリックすると、取得できたアイコン、LAN 広告の MOTD、サーバーの MOTD、アドレス、プレイヤー数、遅延を確認できます。
- IP、IP とポートの組み合わせ、または LAN 広告の MOTD でルームをブロックできます。複数選択してまとめてブロックしたり、ルームが見つからないときでもブロック済みタブから複数のルールを一括解除したりできます。
- ルームの状態照会、待ち時間、既定のブロック条件、マルチプレイ画面のボタンの表示と位置を設定できます。アイコンのみの表示にすると、ドラッグを有効にして左ボタンで配置できます。設定は `config/lanworlds.json` に保存されます。

## ルームのフォルダーと検索

マルチプレイ画面の **…** メニューで、グループ化の切り替え、分類方法の選択、検索、複数選択ができます。Radmin と通常の LAN の区別、IP、ポート、MOTD で分類できます。フォルダーの見出しをクリックすると折りたたみや展開ができ、複数選択中はフォルダーのチェックボックスで、折りたたみ中のルームも含めてまとめて選択や解除ができます。

IP、ポート、MOTD、Radmin ユーザー名で検索できます。メニューが開く向きに応じて、検索候補は入力欄の上または下に表示されます。候補を選ぶと、メインの一覧を絞り込まずに該当ルームへ移動できます。

Ctrl を押しながらルームをクリックすると複数選択を開始できます。クリックで選択を切り替え、Shift を押しながらクリックすると範囲を選択し、Ctrl+Shift なら範囲を追加します。Ctrl+A で全選択でき、ツールバーには全選択、選択の反転、選択解除があります。Esc は最初に選択を解除し、次に複数選択モードを終了します。更新後もアドレスとポートに基づいて選択が復元されます。複数選択中はルームへの参加が一時停止されます。

## Radmin との連携（任意）

LanWorlds は Fabric API と合わせて単体で使えます。同じ Minecraft バージョン用の Radmin Linker 1.0.1 を導入すると、Radmin の仮想 IP と一致するルームにユーザー、ネットワーク、接続情報が表示されます。詳細画面からは Radmin のユーザーやネットワークのプロパティも開けます。青い盾のボタンは LanWorlds の表示と位置の設定に従います。[Radmin Linker](https://github.com/block-fox/radmin-linker)

RadminLAN 0.2.x には旧 LAN ルーム機能が含まれており、LanWorlds とは互換性がありません。`config/lanworlds.json` がない場合は `config/radminlan.json` から照会設定とブロックルールを移行できます。元のファイルはそのまま残ります。

## 開発

Minecraft 26.2 と 26.3 はルートのソースを共有し、JDK 25 を使用します。1.21.11 と 1.21.1 の個別プロジェクトは JDK 21 を使用します。対象バージョンのスクリプトで開発用クライアントを起動してください。

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

ビルドには対応する `scripts/build-*.ps1` を使用します。成果物は `versions/<minecraft-version>/build/` に出力されます。ビルドやバージョンの切り替えの前に、開発用クライアントを終了してください。依存関係がキャッシュされていれば `-Online` は省略できます。通常のチェックアウトは公開 Maven の依存関係を使用します。

回帰チェックは設定の移行、任意の連携、ルームの選択、一括ブロックと解除を対象とします。26.2 では `versions/26.2/gradle.properties` の設定も `-P` 引数で渡します。[連携ドキュメント（中国語）](docs/radmin-integration.md)

```powershell
./gradlew.bat -I tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
```

Mixin のスモークチェックでは開発用クライアントでマルチプレイ一覧と LAN の項目を読み込みます。1.21.1 では右クリックでの詳細表示、複数選択、更新、一括ブロックも確認します。テスト設定は `.tmp/mixin-smoke-runtime/` に保存されます。

```powershell
./gradlew.bat -I tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
```

## 由来とライセンス

LanWorlds は RadminLAN 0.2.0（現 Radmin Linker）の LAN ルーム機能から独立した MOD です。元の MIT ライセンスに従って個別に保守されています。[ライセンス](LICENSE)
