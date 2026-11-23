# ==============================================================================
# Clinic Flow Patient Intake Portal - Production Multi-Stage Dockerfile
# Standalone Mobile-First React + Vite App -> Hardened Nginx Runtime
# ==============================================================================

# Stage 1: Build Production Static Assets
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci --prefer-offline --no-audit

COPY . .
RUN npm run build

# Stage 2: Minimalist Nginx Runtime
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
