# ==============================================================================
# Clinic Flow Frontend - Production Multi-Stage Dockerfile
# Optimized for Installability, Portability (amd64 & arm64), and Zero-Downtime Rollouts
# ==============================================================================

# Stage 1: Build Angular Production Distribution
FROM node:22-alpine AS build
WORKDIR /app

# Install dependencies using clean install
COPY package*.json ./
RUN npm ci --prefer-offline --no-audit

# Build the Angular application
COPY . .
RUN npm run build -- --configuration=production

# Stage 2: Minimalist, Secure Nginx Runtime
FROM nginx:1.27-alpine AS runtime

# Copy custom Nginx configuration with SPA routing and API proxy
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled production artifacts
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80

# Automated container healthcheck for orchestrators (Docker Compose / Swarm / K8s)
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
