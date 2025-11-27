# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it by:

1. **DO NOT** create a public GitHub issue
2. Send details to the repository maintainer privately
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will respond within 48 hours and work on a fix.

## Security Measures

This project implements the following security measures:

### Automated Scanning
- **CodeQL**: Static analysis for Java/Kotlin vulnerabilities
- **Gitleaks**: Secret detection in commits
- **OWASP Dependency Check**: Known vulnerability scanning in dependencies
- **Dependabot**: Automatic dependency updates

### Code Security
- No hardcoded secrets or API keys
- HTTPS enforced for network communications (where applicable)
- Input validation and sanitization
- Secure data storage practices

### Build Security
- Pre-commit hooks for secret detection
- CI/CD security scanning on every push
- Signed releases

## Known Security Considerations

1. **Cleartext Traffic**: The app allows cleartext (HTTP) traffic for compatibility with some web content. This is documented in `network_security_config.xml`.

2. **WebView**: The app uses WebView for displaying content. Content is loaded from trusted sources (Hacker News).

## Security Best Practices for Contributors

1. Never commit secrets, API keys, or credentials
2. Use environment variables for sensitive configuration
3. Keep dependencies updated
4. Follow secure coding guidelines
5. Test security implications of changes
