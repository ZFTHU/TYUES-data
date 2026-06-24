# TYUES-data 데이터베이스

> **현재 버전: 3.0.36**

TYUES-data는 MySQL, MongoDB, S3/MinIO 등 여러 네이티브 프로토콜 어댑터를 지원하는 고성능 자체 개발 문서 데이터베이스 시스템입니다.

## 기능

| 기능 | 설명 |
|------|------|
| 전체 암호화 | AES-256-GCM 데이터 암호화 저장 |
| 다중 프로토콜 | MySQL 5.7-9.0 / MongoDB 3.6-8.0 / S3 |
| 고성능 | 비동기 I/O + 연결 풀 + 인덱스 최적화 |
| 제로컨피그 마이그레이션 | 네이티브 SQL/BSON 문법 호환 |
| 즉시 사용 | 내장 Admin 관리 인터페이스 |

## 빠른 시작

### 환경 요구사항

- Java 17 이상
- 메모리 512MB 이상
- 디스크 공간 1GB 이상

### 빌드 및 실행

```bash
# 빌드
./gradlew clean jar

# 실행
java -jar build/libs/typui-database-*.jar
```

### 기본 설정

| 서비스 | 포트 | 사용자명 | 비밀번호 |
|--------|------|----------|----------|
| HTTP API | 27016 | root | ZM135790.. |
| MySQL | 3306 | root | ZM135790.. |
| MongoDB | 27017 | root | ZM135790.. |
| S3/MinIO | 9000 | root | ZM135790.. |
| Admin UI | 9002 | root | ZM135790.. |

## 연결 예제

### MySQL 연결

```bash
mysql -h localhost -P 3306 -u root -pZM135790..
```

### MongoDB 연결

```javascript
mongosh mongodb://root:ZM135790..@localhost:27017
```

### S3 연결

```bash
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1
```

## 라이선스

이 프로젝트는 MIT 라이선스 하에 라이선스됩니다 - [LICENSE](../LICENSE) 파일 참조
