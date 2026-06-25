# Multi-stage build for Node.js backend

# ── Stage 1: Install dependencies ────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files first for layer caching
COPY package.json package-lock.json* ./

# Install all dependencies (including devDependencies if any)
RUN npm ci --only=production

# ── Stage 2: Production image ────────────────────────────────────────────
FROM node:20-alpine

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -S appuser -u 1001 -G appgroup

WORKDIR /app

# Copy production node_modules from builder stage
COPY --from=builder /app/node_modules ./node_modules

# Copy application source code
COPY . .

# Ensure migrations directory is available
RUN mkdir -p /app/migrations

# Set ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose the application port
EXPOSE 3000

# Health check
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:3000/health || exit 1

# Start the application
CMD ["node", "server.js"]
