# HUB_PlamoScanner (プラモ余剰パーツ管理システム)
> **Metadata**
  * **Last Updated:** 2026-02-11 18:20
  * **Status:** 開発中（基本機能実装済み、AI同期基盤構築中）
  * **Target:** Android (Kotlin/Compose), Python, Notion API
## 1. 要件 (Requirements)
* **目的:** プラモデルの余剰パーツをQRコード/NFCで管理し、Notionデータベース上で「どの袋がどの箱に入っているか」を可視化・検索可能にする。
* **主要機能:**
  * **ハイブリッド・スキャン:** カメラによるQRコード検知と、NFCタグの読み取りを同時に待ち受ける。
  * **「箱にしまう」フロー:** ステップ1で箱を、ステップ2で袋をスキャンし、リレーションを自動更新する。
  * **自動登録:** 未登録のIDをスキャンした際、Notion側にプレースホルダーを自動作成する。
  * **物理ラベル出力:** 100均の44面ラベルに最適化したQRコードPDFの生成。
* **制約事項:**
  * **物理:** ラベル1枚のサイズは 48.3mm × 25.5mm、QRサイズは 17mm。
  * **ハードウェア:** NFC対応のAndroid実機（Rakuten Hand 5G等）。
## 2. 設計 (Design)
* **アーキテクチャ:** - **Frontend:** Jetpack Compose + CameraX + ML Kit。
  * **Backend:** Notion API (Retrofit2 / OkHttp)。
* **データ構造 (Notion):**
  * **袋マスター (HUKURO):** 袋ID (Title), 商品名 (Rich Text), 現在の箱 (Relation)。
  * **箱マスター (HAKO):** 箱ID (Title), 箱名 (Rich Text)。
* **外部連携:**
  * Notion API Version: 2022-06-28。
  * PythonスクリプトによるQR生成: qrcode および reportlab ライブラリを使用。
## 3. タスクと状況 (Tasks & Status)
* **[Done]:**
  * Androidアプリの基本UIとスキャンロジックの実装。
  * Notion APIリポジトリの実装（検索・作成・更新）。
  * PythonによるQRラベル生成スクリプトの完成（余白調整含む）。
* **[Doing]:**
  * Google ドキュメントをハブとした「マルチAI・コンテキスト同期戦略」の構築。
  * docs_sync.py によるローカルMarkdownとDocsの双方向同期実装。
* **[Todo]:**
  * Notion DBの全カラム（スケール、メーカー等）を把握するためのダンプスクリプト作成。
  * エラーハンドリングの強化（ネットワーク切断時の挙動など）。
## 4. 課題と意思決定 (Issues & Decisions)
* **未解決の課題:**
  * VS CodeのGemini AgentモードがWindows環境のアップデート後に不安定（一旦CLI手動運用へ切り替え）。
  * docs_sync.py 実行のための Google Cloud API 認証情報（credentials.json）の準備。
* **決定事項ログ:**
  * **2026-02-11:** ファイル名のプレフィックスを HUB_ に統一し、1ファイルに要件・設計・タスクを集約することを決定。
  * **2026-02-11:** AI間の情報共有を容易にするため、Google ドキュメントを「正」のコンテキスト・ストレージに採用。

