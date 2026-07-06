# メモ帳アプリ (MemoApp)

起動すると即編集画面。ボタンは「開く / 上書き保存 / 名前をつけて保存 / 印刷 / 閉じる」。
印刷はAndroid標準の印刷ダイアログ経由で「PDF形式で保存」が選べます。

## 初回セットアップ手順 (Termux)

新しいリポジトリ `MemoApp` をGitHubで作成してからpushします。

```bash
cp ~/storage/downloads/MemoApp.zip ~/
cd ~
unzip MemoApp.zip -d MemoApp2 2>/dev/null || unzip MemoApp.zip
cd MemoApp
git init
git add .
git commit -m "initial commit"
git branch -M main
git remote add origin https://<TOKEN>@github.com/Sekiguchi-Takashi/MemoApp.git
git push -u origin main
```

## APK取得

GitHubのActionsタブ → 完了したrun → Artifacts「apk」をダウンロード → 解凍して app-debug.apk をインストール。

## カスタマイズ箇所
- アプリ名: app/src/main/res/values/strings.xml
- アイコン: app/src/main/res/drawable/ic_launcher_foreground.xml と values/ic_launcher_background.xml
