# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an AI-powered search application built with Spring Boot 3.5.4 and Java 21. The application integrates Apache Solr for search functionality with Anthropic Claude for intelligent query generation and chat memory for conversational context.

### Core Architecture

- **Spring Boot Application**: Main entry point at `dev.aparikh.aipoweredsearch.AiPoweredSearchApplication`
- **Search Service**: `SearchService` converts free-text queries into structured Solr queries using Claude AI
- **Search Repository**: `SearchRepository` handles Solr interactions and query execution
- **Chat Memory**: Uses PostgreSQL-backed chat memory for conversational context with conversation ID "007"
- **Configuration**: Solr and OpenAPI configuration in `config/` package

### Key Dependencies

- Spring Boot 3.5.4 with Spring AI integration
- Anthropic Claude AI (claude-sonnet-4-0 model)
- Apache Solr 9.6.1 for search
- PostgreSQL for chat memory storage
- Testcontainers for integration testing
- SpringDoc OpenAPI for API documentation

## Development Commands

### Build and Run
```bash
./gradlew build                 # Build the project
./gradlew bootRun              # Run the application
./gradlew clean build          # Clean build
```

### Testing
```bash
./gradlew test                 # Run all tests
./gradlew check               # Run all checks including tests
```

### Single Test Execution
```bash
./gradlew test --tests "ClassName"
./gradlew test --tests "dev.aparikh.aipoweredsearch.search.SearchIntegrationTest"
```

## Configuration Requirements

### Environment Variables
- `ANTHROPIC_API_KEY`: Required for Claude AI integration
- `POSTGRES_USER`: PostgreSQL username (defaults to 'postgres')
- `POSTGRES_PASSWORD`: PostgreSQL password (defaults to 'postgres')

### External Services
- **Solr**: Expected at `http://localhost:8983/solr`
- **PostgreSQL**: Expected at `jdbc:postgresql://localhost:5432/chatmemory`

### API Documentation
- Swagger UI available at `/swagger-ui.html`
- OpenAPI docs at `/api-docs`

## Testing Architecture

The project uses Testcontainers for integration testing with:
- Solr containers for search testing
- PostgreSQL containers for chat memory testing
- Mock configurations for Claude AI in tests
- Separate test configurations in `application-test.properties`

### Test Structure
- `SearchIntegrationTest`: End-to-end search functionality
- `SearchServiceTest`: Unit tests for search service
- `SearchRepositoryIT`: Integration tests for Solr repository
- `SolrTestBase`: Base class for Solr-related tests

## Key Patterns

- AI-powered query transformation from natural language to structured Solr queries
- Conversational search with persistent chat memory
- Field introspection from Solr schema for dynamic query building
- Comprehensive integration testing with external service containers