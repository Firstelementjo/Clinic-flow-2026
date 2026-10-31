FROM node:22-alpine AS builder
WORKDIR /app
RUN apk add --no-cache openssl
RUN corepack enable
COPY package.json yarn.lock tsconfig.base.json tsconfig.build.json ./
COPY apps ./apps
COPY packages ./packages
RUN yarn install --frozen-lockfile
RUN yarn workspace @firstelement/database prisma:generate
RUN yarn tsc -b tsconfig.build.json

FROM node:22-alpine AS runner
WORKDIR /app
RUN apk add --no-cache openssl
RUN corepack enable
ENV NODE_ENV=production
COPY --from=builder /app /app
EXPOSE 4000
CMD ["sh", "-c", "node apps/api/dist/main.js & node apps/worker/dist/main.js & wait"]
