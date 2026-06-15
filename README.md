# TYPUI Database

A high-performance, self-developed document database with protocol adapters for MySQL, MongoDB, and S3/MinIO compatibility.

## Features

- **Document Storage**: Native JSON document database with encryption support
- **MySQL Protocol**: Wire protocol compatible with MySQL 5.7 - 9.0 clients
- **MongoDB Protocol**: Wire protocol compatible with MongoDB 3.6 - 8.0 clients  
- **S3/MinIO Compatible**: REST API compatible with S3 SDKs and MinIO clients
- **Full Encryption**: AES-256-GCM encryption for all data at rest
- **Multi-protocol Support**: Exposes native protocols for seamless migration

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Protocol Adapters                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ MySQL    │  │ MongoDB  │  │   S3     │  │  HTTP    │    │
│  │ 3306/TCP │  │27017/TCP │  │ 9000/HTTP│  │27016/HTTP│    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘    │
└───────┼─────────────┼─────────────┼─────────────┼──────────┘
        │             │             │             │
        ▼             ▼             ▼             ▼
┌─────────────────────────────────────────────────────────────┐
│                   Unified Database Layer                     │
│              DocumentDatabase + StorageServer                │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    Encrypted Storage                         │
│              AES-256-GCM + File System                      │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start

### Prerequisites
- Java 17 or higher
- Gradle 8.5+

### Build
```bash
./gradlew clean jar
```

### Run
```bash
java -jar build/libs/typui-database-*.jar
```

### Configuration

Default credentials:
- Username: `root`
- Password: `ZM135790..`

### Supported Clients

**MySQL Clients:**
```bash
mysql -h localhost -P 3306 -u root -pZM135790..
```

**MongoDB Clients:**
```bash
mongosh mongodb://root:ZM135790..@localhost:27017
```

**S3/MinIO Clients:**
```bash
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1
```

## Supported Versions

| Protocol | Versions | Port |
|----------|----------|------|
| MySQL | 5.7, 8.0, 8.1-8.5, 9.0 | 3306 |
| MongoDB | 3.6, 4.0-4.4, 5.0, 6.0, 7.0, 8.0 | 27017 |
| S3 | AWS S3 / MinIO compatible | 9000 |
| HTTP API | Native REST | 27016 |

## Security

- All data stored with AES-256-GCM encryption
- Support for mysql_native_password and caching_sha2_password authentication
- Secure session management with token expiration

## License

This project is for internal use only.

## Contributing

Please refer to internal documentation for contribution guidelines.
