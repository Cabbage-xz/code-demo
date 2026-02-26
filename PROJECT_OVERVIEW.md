# Java Code-Demo Project Overview

## Project Structure
This is a multi-module Maven project with the following modules:

1. **ai-doc-qa-demo** - An AI-powered document Q&A system using LangChain4j and vector embeddings
2. **route-demo** - A dynamic routing system for database operations with multi-environment support
3. **fault-data-sync-demo** - A daily fault-log synchronization pipeline backed by RocketMQ and PowerJob

## Main Project (Parent)
- **Artifact ID**: code-demo
- **Version**: 0.0.1-SNAPSHOT
- **Framework**: Spring Boot 3.5.6
- **Java Version**: 17
- **Dependencies**: Spring Web, MySQL Connector, Lombok

## Module Descriptions

### 1. ai-doc-qa-demo
An intelligent document question-answering system that allows users to upload documents and ask questions about their content using AI.

**Technology Stack**:
- Spring Boot 3.5.7
- LangChain4j 0.35.0
- OpenAI-compatible API (configured for DeepSeek)
- BGE Small Chinese Embedding Model
- Apache PDFBox and Apache POI for document parsing

**Key Features**:
- Upload and process various document formats (PDF, Word, etc.)
- Vector storage for document chunks
- Natural language querying of document content
- REST API for document loading and chat interactions

**Main Components**:
- ChatController: Handles chat requests and document loading
- AiChatService: Manages AI interactions
- DocumentService: Handles document processing and vector storage
- Configuration classes for DeepSeek, LangChain, and Vector Store

### 2. route-demo
A dynamic routing system that enables switching between different database environments/data sources based on request context.

**Technology Stack**:
- Spring Boot 3.5.6
- MyBatis-Plus 3.5.6
- MySQL
- Hutool utility library

**Key Features**:
- Dynamic routing between different database instances (e.g., beta vs comm environments)
- Annotation-based routing configuration (@RouteCustom)
- Context-aware routing with ThreadLocal
- Factory pattern for managing routed beans
- Support for both mapper and service level routing

**Main Components**:
- BeanRouteFactory: Central factory for obtaining routed beans
- RouteCustom annotation: Defines routing configuration
- RouteContext: Maintains routing context per thread
- FaultController: Example controller showing routing in action
- Various entity, mapper, service implementations for different environments

### 3. fault-data-sync-demo
A daily batch synchronization pipeline that pulls device-reported fault logs from an upstream data source, publishes them to RocketMQ in 5,000-record batches, and persists them to MySQL — with PowerJob scheduling and full resync support for 5-day historical data.

**Technology Stack**:
- Spring Boot 3.5.6
- PowerJob 4.3.6 (distributed job scheduling)
- RocketMQ 2.3.0 (reliable async messaging)
- MyBatis-Plus 3.5.6
- MySQL

**Key Features**:
- rank-based cursor pagination against upstream API (5,000 records per page)
- 20 domains processed in parallel via a bounded thread pool (CallerRunsPolicy back-pressure)
- Full-overwrite resync strategy (DELETE then INSERT) for D-1 through D-5
- Layered idempotency: full overwrite as primary + INSERT IGNORE + unique index as safety net
- MQ retry (maxReconsumeTimes=3) → DLQ → FAILED marking and alert hook
- Atomic batch-completion tracking via a single `UPDATE … CASE WHEN` SQL
- Configurable mock data source for local testing (up to 1,000,000 records per domain/date)

**Main Components**:
- FaultDataSyncJob: PowerJob BasicProcessor, orchestrates parallel CompletableFuture tasks
- FaultSyncServiceImpl: core loop (delete → pull → MQ send)
- FaultDataConsumer: INSERT IGNORE in 1,000-row chunks, increments completed-batch counter
- FaultDataDlqConsumer: DLQ consumer, marks task FAILED, extendable alerting hook
- MockFaultDataSourceClient: rank-cursor mock, configurable total volume

---

## Build and Run Instructions
All modules can be built and run independently using Maven:

For ai-doc-qa-demo:
```bash
cd ai-doc-qa-demo
./mvnw spring-boot:run
```

For route-demo:
```bash
cd route-demo
./mvnw spring-boot:run
```

For fault-data-sync-demo:
```bash
# Prerequisites: MySQL, RocketMQ name-server, PowerJob server all running locally
cd fault-data-sync-demo
./mvnw spring-boot:run
```