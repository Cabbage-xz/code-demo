# AI Document Q&A Demo Module Documentation

## Overview
The ai-doc-qa-demo module is an intelligent document question-answering system that leverages AI and vector embeddings to allow users to interact with their documents through natural language queries.

**Module Name**: ai-doc-qa
**Type**: Spring Boot Application
**Purpose**: AI-powered document question-answering system

## Architecture
The module follows a typical Spring Boot MVC architecture with additional AI/ML components:

```
Controllers
├── ChatController          # Handles API requests

Services
├── AiChatService           # Manages AI chat interactions
├── DocumentService         # Handles document processing and storage

Configuration
├── DeepSeekConfig          # Configures DeepSeek API integration
├── LangChainConfig         # Sets up LangChain4j components
└── VectorStoreConfig       # Configures vector storage

Models/Requests/Responses
├── ChatReq                 # Request model for chat interactions
├── ChatResp                # Response model for chat results
└── Result                  # Generic response wrapper
```

## Key Features

### Document Processing
- Supports multiple document formats (PDF, DOC, DOCX)
- Uses Apache PDFBox and Apache POI for document parsing
- Employs BGE Small Chinese embedding model for Chinese language support
- Stores document chunks in an in-memory vector store

### AI Chat Interface
- Provides natural language interaction with documents
- Integrates with OpenAI-compatible APIs (configured for DeepSeek)
- Uses retrieval-augmented generation (RAG) pattern to answer questions based on document content

### API Endpoints
- `GET /api/ai/health` - System health check
- `POST /api/ai/chat` - Chat with documents
- `POST /api/ai/document/load` - Load documents from specified path
- `POST /api/ai/documents/clear` - Clear vector store

## Configuration Properties
- `document.base-path` - Base path for document loading
- DeepSeek API settings configured through application properties

## Technology Stack
- **Framework**: Spring Boot 3.5.7
- **AI Framework**: LangChain4j 0.35.0
- **Embedding Model**: BGE Small Chinese
- **Document Parsing**: Apache PDFBox, Apache POI
- **API Client**: OpenAI-compatible integration

## Dependencies
- Spring Boot Web
- Spring Boot Validation
- Lombok
- Jackson Databind
- LangChain4j core
- LangChain4j OpenAI (for DeepSeek compatibility)
- LangChain4j BGE Chinese embeddings
- Document parsers for PDF and Office formats

## Usage
1. Start the application
2. Load documents using `/api/ai/document/load` endpoint
3. Query documents using `/api/ai/chat` endpoint
4. Use `/api/ai/documents/clear` to clear the vector store when needed

## Security Considerations
- Input validation on document paths to prevent directory traversal
- API key management for DeepSeek integration
- Proper error handling to avoid leaking sensitive information