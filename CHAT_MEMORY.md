# PostgreSQL-based Chat Memory for Spring AI

This document explains how to use PostgreSQL for storing chat memory in the AI-powered search application.

## Overview

The application uses Spring AI's chat memory feature to maintain conversation history between the user and the AI model. By default, Spring AI uses an in-memory repository for chat memory, which is lost when the application restarts. This implementation uses PostgreSQL to persistently store chat memory, allowing conversations to continue across application restarts.

## Configuration

### Dependencies

The following dependencies have been added to the project:

```kotlin
// PostgreSQL driver for JDBC chat memory
implementation("org.postgresql:postgresql:42.7.2")

// For testing
testImplementation("org.testcontainers:postgresql")
```

### Database Configuration

The application is configured to use PostgreSQL for chat memory storage with the following properties in `application.properties`:

```properties
# PostgreSQL Database Configuration
spring.datasource.url=jdbc:postgresql://localhost:5432/chatmemory
spring.datasource.username=${POSTGRES_USER:postgres}
spring.datasource.password=${POSTGRES_PASSWORD:postgres}
spring.datasource.driver-class-name=org.postgresql.Driver

# Chat Memory Configuration
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
spring.ai.chat.memory.repository.type=jdbc
```

- `spring.datasource.*`: Standard Spring Boot properties for configuring the database connection.
- `spring.ai.chat.memory.repository.jdbc.initialize-schema=always`: Tells Spring AI to automatically create the necessary database schema for chat memory.
- `spring.ai.chat.memory.repository.type=jdbc`: Specifies that the JDBC repository should be used for chat memory.

### Environment Variables

The following environment variables can be set to customize the database connection:

- `POSTGRES_USER`: The PostgreSQL username (default: postgres)
- `POSTGRES_PASSWORD`: The PostgreSQL password (default: postgres)

## Usage

The chat memory is automatically used by the `SearchService` through the `MessageChatMemoryAdvisor`. No additional code changes are needed to use the PostgreSQL-based chat memory.

### How It Works

1. When the application starts, Spring AI creates the necessary database schema for chat memory.
2. The `ChatMemory` bean is injected into the `SearchService` constructor.
3. The `SearchService` creates a `ChatClient` with a `MessageChatMemoryAdvisor` that uses the `ChatMemory`.
4. When a user sends a query, the `ChatClient` stores the conversation history in the PostgreSQL database.
5. In subsequent queries, the `ChatClient` retrieves the conversation history from the database and includes it in the prompt to the AI model.

## Testing

A test configuration and integration test have been added to verify the PostgreSQL-based chat memory:

- `PostgresTestConfiguration`: Creates a PostgreSQL container for testing.
- `ChatMemoryIntegrationTest`: Tests that messages can be stored and retrieved from the PostgreSQL database.

To run the tests:

```bash
./gradlew test
```

## Database Schema

Spring AI automatically creates the following tables in the PostgreSQL database:

- `chat_memory`: Stores the chat memory metadata.
- `chat_memory_message`: Stores the individual messages in the chat memory.

You can connect to the database and query these tables to see the stored chat memory:

```sql
SELECT * FROM chat_memory;
SELECT * FROM chat_memory_message;
```

## Troubleshooting

If you encounter issues with the PostgreSQL-based chat memory:

1. Verify that PostgreSQL is running and accessible at the configured URL.
2. Check the database logs for any errors related to the chat memory tables.
3. Ensure that the database user has the necessary permissions to create tables and insert data.
4. If the schema is not being created automatically, you can set `spring.ai.chat.memory.repository.jdbc.initialize-schema=always` to force schema creation.