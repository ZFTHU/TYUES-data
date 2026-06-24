# TYUES-data Database

> **Current Version: 3.0.36**

TYUES-data is a high-performance self-developed document database system with protocol adapters for MySQL, MongoDB, and S3/MinIO, enabling seamless database migration without code changes.

## Features

| Feature | Description |
|---------|-------------|
| Full Encryption | AES-256-GCM encrypted data storage |
| Multi-Protocol | MySQL 5.7-9.0 / MongoDB 3.6-8.0 / S3 |
| High Performance | Async I/O + Connection Pool + Index Optimization |
| Zero-Config Migration | Compatible with native SQL/BSON syntax |
| Out of the Box | Built-in Admin management interface |

## Quick Start

### Requirements

- Java 17 or higher
- 512MB+ RAM
- 1GB+ Disk space

### Build and Run

```bash
# Build
./gradlew clean jar

# Run
java -jar build/libs/typui-database-*.jar
```

### Default Configuration

| Service | Port | Username | Password |
|---------|------|----------|----------|
| HTTP API | 27016 | root | ZM135790.. |
| MySQL | 3306 | root | ZM135790.. |
| MongoDB | 27017 | root | ZM135790.. |
| S3/MinIO | 9000 | root | ZM135790.. |
| Admin UI | 9002 | root | ZM135790.. |

## Connection Examples

### MySQL Connection

```bash
mysql -h localhost -P 3306 -u root -pZM135790..
```

### MongoDB Connection

```javascript
mongosh mongodb://root:ZM135790..@localhost:27017
```

### S3 Connection

```bash
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1
```

## Documentation

- [Installation Guide](INSTALL_EN.md) - Detailed installation steps
- [Configuration](CONFIG_EN.md) - Configuration file reference
- [API Reference](API_EN.md) - REST API documentation
- [Protocols](PROTOCOL_EN.md) - MySQL/MongoDB/S3 protocol details
- [Security](SECURITY_EN.md) - Security configuration and best practices

## License

This project is licensed under MIT License - see [LICENSE](../LICENSE) file
