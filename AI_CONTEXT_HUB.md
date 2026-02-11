# AI Context Hub - PlamoScanner

このファイルは、異なる環境（Android Studio, Web版Gemini, Xcode等）のAI間でプロジェクトの状況を共有するための「コンテキスト・ハブ」です。

## 1. プロジェクト概要
- **名称**: PlamoScanner
- **目的**: プラモデルの余剰パーツを「袋」に入れ、それらを「箱」に収納するプロセスをRFID (NFC) および QRコードで管理する。
- **プラットフォーム**: Android (Kotlin / Jetpack Compose) ※将来的にiOS対応を視野。
- **バックエンド**: Notion API

## 2. システムアーキテクチャ
- **UI層 (`MainActivity.kt`)**: 状態管理（スキャンモード、ID、メッセージ）とUI表示。
- **コンポーネント (`QrScannerView.kt`)**: CameraX + ML KitによるQRコード解析。
- **リポジトリ層 (`NotionRepository.kt`)**: 「検索→なければ作成」のビジネスロジックの共通化。
- **通信層 (`NotionClient.kt`)**: Retrofit2によるNotion APIの定義（Query, Create, Update）。
- **設定 (`SecretConfig.kt`)**: APIキーとDatabase IDの保持（.gitignore対象）。

## 3. Notionデータベース仕様
### A. 袋マスター (HUKURO)
- `袋ID` (Title): RFIDのUID、またはQRの文字列 (PK)
- `商品名` (Rich Text): パーツの名称（デフォルト: "新規登録パーツ"）
- `現在の箱` (Relation): HAKOデータベースへのリレーション

### B. 箱マスター (HAKO)
- `箱ID` (Title): RFIDのUID、またはQRの文字列 (PK)
- `箱名` (Rich Text): 箱の名称（デフォルト: "新しい箱"）

## 4. 主要なロジックと機能
- **ハイブリッド・スキャン**: 画面下半分でカメラ(QR)を常時起動し、背面のNFCと同時に待ち受ける。先に検知したIDを採用。
- **「箱にしまう」フロー**: 箱をスキャン(Step1) -> 袋をスキャン(Step2)。袋レコードの「現在の箱」リレーションを箱のPage IDで更新する。
- **自動レコード作成**: スキャンしたIDがNotionにない場合、即座にデフォルト値でINSERTし、そのまま処理を継続する。
- **ユーザーフィードバック**:
    - **シャッター音**: `MediaActionSound` による「カシャッ」という音。
    - **フラッシュ**: Composeの `AnimatedVisibility` による画面の白い暗転。
    - **インターバル**: 誤検知防止のため、スキャン成功後0.5秒間は入力をロック。

## 5. 次のステップ / 今後の課題
- **iOS対応 (Compose Multiplatform)**: `NotionRepository` 等のロジックを共通モジュールに切り出し、iOS用のカメラ実装を追加する。
- **Notion連携の深化**: 登録成功後にNotionアプリで特定のページを直接開く `Intent` 処理の最適化。
- **データの正規化**: QRコードの形式は `"YYYY/MM/DD HH:MM:SS.000"` とする。

## 6. AIへの指示（コンテキスト投入用）
「このプロジェクトは、AndroidでNFCとQRを使い、NotionをDBとしてプラモパーツを管理するアプリです。現在はAndroid版が完成しており、NotionRepositoryでDRY原則に基づき共通化されています。今後はiOS対応やUIのブラッシュアップを検討しています。現在のコードベースを前提に回答してください。」
