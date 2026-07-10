# ---- deps ----
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# Build scripts (esbuild/sharp) are allowlisted in package.json; CI installs run them.
RUN npm ci

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Next standalone output (next.config output:"standalone") -> minimal runtime bundle.
RUN npm run build

# ---- run ----
FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
# Non-root, same discipline as the backend image.
RUN addgroup -g 1001 nodejs && adduser -u 1001 -G nodejs -S nextjs
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
