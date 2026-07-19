# MemoApp 引き継ぎ仕様書

新しいチャットで開発を継続する際は、このファイルを含むソースzipを添付し
「MemoAppの更新を継続します。HANDOFF.mdのルールを厳守してください」と伝えること。

現行バージョン: versionName 1.2 / versionCode 3

---

## 1. 開発環境(変更禁止)

| 項目 | 内容 |
|---|---|
| 編集環境 | スマートフォンのTermux(PCなし) |
| コンパイル | GitHub Actions のみ。ローカルビルドは行わない |
| リポジトリ | Sekiguchi-Takashi/MemoApp |
| Gradle | gradle-wrapper を置かない。Actions側で 8.9 を固定セットアップ |
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| SDK | compileSdk 34 / targetSdk 34 / minSdk 26 |
| JDK | 17 (temurin) |
| 依存関係 | **外部ライブラリ依存ゼロ**。Android SDK標準APIのみ使用 |
| UI | XMLレイアウトを使わず、全てKotlinコードで動的に構築 |
| 署名 | リポジトリ内 `app/debug.keystore` 固定(storepass/keypass = android, alias = androiddebugkey)。**再生成禁止**。これにより毎回アンインストール不要で上書きインストールできる |
| 画面 | portrait 固定(手書きキャンバスが回転で消えるのを防ぐため) |

## 2. 更新の受け渡しルール

1. 変更したファイル**だけ**を、リポジトリルートからの相対パス
   (例: `app/src/main/java/com/sekiguchi/memoapp/MainActivity.kt`)を保ったままzip化して渡す。
   フルパスやトップフォルダを含めないこと(`unzip -o ... -d ~/MemoApp` で正しく上書きされる形にする)。
2. 毎回 `app/build.gradle.kts` の versionCode を +1、versionName も上げる。
3. zipに `.bash_history` などTermuxのホームファイルを絶対に含めない
   (過去にGitHubのPush Protectionでトークン検出によりpush拒否された事故あり)。

利用者側の適用手順:

```bash
cp ~/storage/downloads/<zip名>.zip ~/
unzip -o ~/<zip名>.zip -d ~/MemoApp
cd ~/MemoApp
git add .
git commit -m "MemoApp vX <変更概要>"
git push
```

その後 GitHub の Actions タブ → 完了したrun → Artifacts「apk」→ 解凍 → `app-debug.apk` をインストール。

## 3. ファイル構成

```
MemoApp/
├── .github/workflows/build.yml          # assembleDebug してAPKをArtifact化
├── settings.gradle.kts                  # repositories定義 + include(":app")
├── build.gradle.kts                      # AGP/Kotlinのバージョン宣言(apply false)
├── README.md
├── HANDOFF.md                            # このファイル
└── app/
    ├── build.gradle.kts                  # namespace, version, signingConfig(debug.keystore)
    ├── debug.keystore                    # 固定署名鍵(コミット済み)
    └── src/main/
        ├── AndroidManifest.xml           # MainActivity単体, portrait, adjustResize
        ├── java/com/sekiguchi/memoapp/
        │   └── MainActivity.kt           # 全機能がここに集約(単一ファイル)
        └── res/
            ├── values/strings.xml                 # アプリ名「メモ帳」
            ├── values/ic_launcher_background.xml  # アイコン背景色
            ├── drawable/ic_launcher_foreground.xml# アイコン前景(ベクター)
            └── mipmap-anydpi-v26/ic_launcher.xml  # アダプティブアイコン
```

パッケージ名 / applicationId: `com.sekiguchi.memoapp`

## 4. アプリ仕様(MainActivity.kt 内で5画面を切り替え)

`setContentView()` を差し替える方式で、Activityは1つのみ。`showScreen(n)` が切替の中心。
`onBackPressed()` は 画面2/3/4 → 画面1、画面5 → 画面4 に戻る。

### 画面1: テキストページ(起動時)
ボタン: 開く / 保存 / 手書き / 印刷 / 保持 / 閉じる

- **開く**: SAF(ACTION_OPEN_DOCUMENT)でテキストファイルを読み込み。UTF-8。
- **保存**: ポップアップで「上書き保存 / 名前をつけて保存」を選択。
  上書き先が未設定なら自動で「名前をつけて保存」へフォールバック。
- **手書き**: 画面4へ遷移。
- **印刷**: 本文をHTML(`<pre>` 折り返し)化しWebView経由でPrintManagerへ。A4。
  Androidの印刷ダイアログから「PDF形式で保存」が選べる。
- **保持**: 画面2へ遷移。
- **閉じる**: 未保存なら確認後にアプリ終了。
- 未保存の変更があるとファイル名表示に `*` が付く。

### 画面2: 保持ページ
ボタン: 保持 / 削除 / 機密 / 戻る + 保持1〜10の行

- 各行の状態は EMPTY(0) / HELD(1) / SECRET(2)。
- **保持**: 入力済みの空欄行を固定化。行頭に「コピー」ボタンが出る。
- **削除**: 1回目でHELD行にチェックボックス表示(ボタン表記が「削除実行」に変化)、
  選択して2回目で行を空欄に初期化。**SECRET行にはチェックボックスを出さない**(画面2から削除不可)。
- **機密**: 入力が2行以上あるとエラーダイアログで1行ずつを強制。
  1回目は4桁数字のパスワード設定(保存/キャンセル)、2回目以降は確認ダイアログ。
  登録すると行は `●●●●●●` 表示になり「機密情報」ボタンが出る。
- **機密情報**ボタン: パスワード入力ダイアログ(OK / キャンセル / 完全削除)。
  正解なら画面3へ。完全削除はパスワード不要で、確認ダイアログに `Delete` と入力すると
  その行を初期化(パスワード忘れの救済措置)。
- SECRET行が全て消えるとパスワードハッシュも自動リセットされ、次回は新規設定に戻る。

### 画面3: 機密情報ページ
ボタン: 削除 / 戻る(戻るは画面1へ)

- SECRET行の一覧。パスワードを通した行(`revealed`)のみ本文とコピーボタンを表示、
  他は「ロック中」表示。
- 削除は表示中の行のみ選択可能で、画面2と同じ2段階方式。

### 画面4: 手書きページ
ボタン: 開く / 保存 / テキスト / 印刷 / 保持 / 初期化

- 内部クラス `DrawView`(Viewを継承)がキャンバス。Bitmap + Canvas + Path で描画、
  `onSizeChanged` でBitmap生成、`setImage()` は縦横比を保って中央にフィット。
- **開く**: `image/*` を選択し、写真等を読み込んで上から手書きできる。
- **保存**: 上書き/名前をつけて を選択。PNG形式。
- **テキスト**: 画面1へ戻る。
- **印刷**: BitmapをBase64のdata URIでHTMLに埋めてWebView印刷。
- **保持**: 画面5へ。最大5枚、埋まっているとエラーダイアログ。
- **初期化**: 確認後にキャンバスを白紙化。

### 画面5: 手書き保持ページ
ボタン: 戻る(画面4へ) + 保持1〜5の行

- 各行にサムネイル、隣に「削除」(そのPNGを消去)と「移す」(キャンバスに呼び戻す)。
- 「移す」はコピー動作で、保持側のデータは残る。キャンバスの描きかけは上書きされる。

## 5. 永続化

| データ | 保存先 |
|---|---|
| 保持1〜10のテキストと状態 | SharedPreferences "hold" キー `slots`(JSONArray) |
| 機密パスワード | 同 "hold" キー `pw`(SHA-256ハッシュ) |
| 手書き保持1〜5 | 内部ストレージ `filesDir/draw_hold_0.png` 〜 `draw_hold_4.png` |

注意: 機密パスワードは表示ゲートであり暗号化ではない。本文は平文でSharedPreferencesに入る。
本物の認証情報の保管用途には使わない前提。

## 6. 過去に踏んだ落とし穴

- zipにトップフォルダを含めずホーム直下で解凍 → `git add .` でTermuxの `.bash_history` を巻き込み、
  中にトークンが記録されていたため GitHub Push Protection (GH013) でpush拒否。
  解決は `rm -rf ~/.git` してプロジェクトを `~/MemoApp/` へ移動しやり直し。
  なお blocked時の「unblock-secret」URLは使わないこと(トークンが公開される)。
- `git init` はプロジェクトフォルダ内で行う。ホーム直下で行わない。
- ブレース展開が効かない環境があるため、mkdir は展開に頼らず個別指定が安全。
