# Session policy and refresh-token draft

Default JWT lifetime: 7 days (604800 seconds), rather than 24 hours. Existing JWT_EXPIRATION_SECONDS overrides remain authoritative. Existing tokens retain their original expiry. This does not deploy or modify any environment.

Refresh tokens are a future design, not an implemented endpoint: random opaque tokens stored as hashes server-side; rotation on each refresh, reuse revokes the family; revoke on logout/account deletion/password reset. Access tokens should become short-lived only when refresh is implemented. Include CSRF protection for cookie transport and platform-appropriate secure storage for native clients. Add the owner table to AccountDataDeletion and its PostgreSQL coverage test.

Redis unavailable: Caffeine counters mirror healthy requests, then enforce the same IP/account limits locally. A 60-second circuit avoids repeating Redis connection timeouts. Counts are process-local during outage; distributed enforcement still requires Redis. Redis connect/read timeouts are 2 seconds. JWT lifetime and fallback behavior need release review; no production configuration was changed.
