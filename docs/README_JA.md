# TYUES-data データベース

TYUES-dataは、MySQL、MongoDB、S3/MinIOなどの複数のネイティブプロトコルアダプタを備えた高性能自己開発ドキュメントデータベースシステムです。

## 機能

| 機能 | 説明 |
|------|------|
| フル暗号化 | AES-256-GCMデータ暗号化ストレージ |
| マルチプロトコル | MySQL 5.7-9.0 / MongoDB 3.6-8.0 / S3 |
| 高性能 | 非同期I/O + 接続プール + インデックス最適化 |
| ゼロコンフィグ移行 | ネイティブSQL/BSON構文互換 |
| すぐ使える | 内蔵Admin管理インターフェース |

## クイックスタート

### 環境要件

- Java 17以上
- メモリ 512MB以上
- ディスク容量 1GB以上

### ビルドと実行

```bash
# ビルド
./gradlew clean jar

# 実行
java -jar build/libs/typui-database-*.jar
```

### デフォルト設定

| サービス | ポート | ユーザー名 | パスワード |
|----------|--------|------------|------------|
| HTTP API | 27016 | root | ZM135790.. |
| MySQL | 3306 | root | ZM135790.. |
| MongoDB | 27017 | root | ZM135790.. |
| S3/MinIO | 9000 | root | ZM135790.. |
| Admin UI | 9002 | root | ZM135790.. |

## 接続例

### MySQL接続

```bash
mysql -h localhost -P 3306 -u root -pZM135790..
```

### MongoDB接続

```javascript
mongosh mongodb://root:ZM135790..@localhost:27017
```

### S3接続

```bash
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1
```

## ライセンス

このプロジェクトはMITライセンスの下でライセンスされています - [LICENSE](../LICENSE)ファイルを参照
