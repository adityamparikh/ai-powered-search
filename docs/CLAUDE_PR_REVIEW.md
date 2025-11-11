# Claude AI PR Review Integration

This repository uses Claude AI to automatically review pull requests and provide intelligent feedback on code changes.

## Overview

When a pull request is opened or updated, Claude AI automatically:
1. Analyzes the code changes
2. Provides feedback on code quality, security, performance, and best practices
3. Posts a detailed review comment
4. Sets the PR status based on critical issues found

## Features

### Automated Code Review
- **Comprehensive Analysis**: Reviews code for quality, security, performance, and adherence to best practices
- **Context-Aware**: Understands Spring Boot, Java, and AI/ML patterns specific to this project
- **Actionable Feedback**: Provides specific suggestions with code examples

### Review Categories

#### 1. Code Quality
- Clean code principles
- SOLID principles compliance
- Design patterns usage
- Maintainability and readability
- Naming conventions

#### 2. Security
- SQL injection vulnerabilities
- XSS vulnerabilities
- Authentication/authorization issues
- Sensitive data exposure
- OWASP Top 10 compliance

#### 3. Performance
- Algorithm efficiency
- Database query optimization
- Memory management
- Resource leak detection
- Caching opportunities

#### 4. Spring Boot Best Practices
- Spring conventions
- RESTful API design
- Dependency injection patterns
- Configuration management
- Actuator endpoints security

#### 5. AI/ML Specific
- Prompt engineering quality
- Vector store usage efficiency
- Embedding model configuration
- RAG implementation correctness
- Token usage optimization

## Setup Instructions

### 1. Add Anthropic API Key

Add your Anthropic API key as a GitHub secret:

1. Go to Settings → Secrets and variables → Actions
2. Create a new repository secret named `ANTHROPIC_API_KEY`
3. Add your Anthropic API key value

### 2. Enable GitHub Actions

Ensure GitHub Actions are enabled for your repository:
1. Go to Settings → Actions → General
2. Select "Allow all actions and reusable workflows"

### 3. Configure Review Settings

The review behavior can be customized in `.github/claude-review-config.yml`:

```yaml
# Enable/disable reviews
enabled: true

# Review model and parameters
review:
  model: claude-3-5-sonnet-20241022
  max_tokens: 4000
  temperature: 0

# File patterns to include/exclude
include_patterns:
  - "*.java"
  - "*.kt"
  - "build.gradle*"

exclude_patterns:
  - "**/test/**"
  - "**/generated/**"
```

## Usage

### Automatic Reviews

Claude AI automatically reviews PRs when:
- A new PR is opened
- Commits are pushed to an existing PR
- A PR is reopened

### Review Output

The review appears as a comment on the PR with:
- **Summary**: Overview of changes
- **Strengths**: What's done well
- **Issues Found**: Categorized by severity
  - Critical (Must Fix)
  - Important (Should Fix)
  - Suggestions (Nice to Have)
- **Code Examples**: Specific improvement suggestions

### PR Status

The workflow sets the PR status:
- ✅ **Success**: No critical issues found
- ❌ **Failure**: Critical issues that must be addressed

## Interpreting Reviews

### Severity Levels

1. **Critical** (🔴): Must be fixed before merging
   - Security vulnerabilities
   - Breaking changes
   - Data loss risks

2. **Important** (🟠): Should be addressed
   - Performance issues
   - Code quality problems
   - Missing error handling

3. **Suggestions** (🟡): Nice to have
   - Style improvements
   - Refactoring opportunities
   - Documentation enhancements

### Example Review

```markdown
## 🤖 Claude AI Code Review

## Summary
Added new search endpoint with vector similarity support. The implementation is solid but has some security concerns.

## Strengths
- Well-structured REST endpoint
- Proper use of Spring annotations
- Good error handling

## Issues Found

### Critical (Must Fix)
- **SQL Injection Risk**: Line 45 - User input is directly concatenated into query
  ```java
  // Instead of:
  String query = "SELECT * FROM " + tableName;
  // Use:
  String query = "SELECT * FROM ?";
  ```

### Important (Should Fix)
- **Missing Input Validation**: The query parameter should be validated for max length

### Suggestions (Nice to Have)
- Consider adding @Cacheable annotation for frequently accessed data
```

## Customization

### Modifying Review Focus

Edit `.github/claude-review-config.yml` to adjust review priorities:

```yaml
focus_areas:
  security:
    enabled: true
    priority: critical  # critical, high, medium, low

  performance:
    enabled: true
    priority: high
```

### Adding Custom Context

Provide project-specific context in the config:

```yaml
custom_prompts:
  project_context: |
    This project uses Spring AI with Anthropic Claude and OpenAI.
    Focus on vector store efficiency and prompt engineering.
```

## Best Practices

### For Developers

1. **Wait for Review**: Allow Claude to complete the review before merging
2. **Address Critical Issues**: Always fix critical issues identified
3. **Consider Suggestions**: Evaluate suggestions for code improvement
4. **Provide Context**: Add clear PR descriptions to help Claude understand changes

### For Reviewers

1. **Claude as Assistant**: Use Claude's review as a starting point, not the final word
2. **Domain Knowledge**: Apply domain-specific knowledge Claude might miss
3. **Human Judgment**: Some decisions require human context and judgment

## Limitations

- **Token Limits**: Large PRs may be truncated (max ~30,000 characters)
- **Context Window**: Cannot access the entire codebase, only changed files
- **No Execution**: Cannot run or test the code
- **Language Support**: Best with Java, Kotlin, and common config files

## Troubleshooting

### Review Not Appearing

1. Check GitHub Actions tab for workflow status
2. Verify `ANTHROPIC_API_KEY` secret is set
3. Check workflow logs for errors

### Incorrect Review

1. Ensure PR description provides context
2. Check if file patterns in config match your changes
3. Verify the model version in config is current

### Performance Issues

1. Large PRs may take longer to review
2. Consider splitting large PRs into smaller ones
3. Exclude generated or vendored files in config

## Cost Management

- Each review uses Claude API tokens
- Approximate cost: $0.003-0.015 per review
- Configure rate limits in `.github/claude-review-config.yml`:

```yaml
rate_limit:
  max_per_hour: 10
  max_per_pr: 3
```

## Security Considerations

- **API Key Security**: Never commit API keys to the repository
- **Secret Management**: Use GitHub Secrets for sensitive data
- **Review Visibility**: Reviews are public on public repositories
- **Data Privacy**: Code snippets are sent to Anthropic's API

## Contributing

To improve the Claude review integration:

1. Edit workflow: `.github/workflows/claude-pr-review.yml`
2. Update config: `.github/claude-review-config.yml`
3. Test changes in a separate PR
4. Document any new features

## Support

For issues with the Claude PR review integration:
1. Check the [troubleshooting](#troubleshooting) section
2. Review GitHub Actions logs
3. Open an issue with the `claude-review` label

---

*This integration enhances code quality through AI-powered reviews while maintaining human oversight for final decisions.*