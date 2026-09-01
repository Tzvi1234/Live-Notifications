# syntax=docker/dockerfile:1

# Optional alternative to Render's native Node runtime (set `runtime: docker` in render.yaml,
# or use this to run Kickoff anywhere else). The native runtime is the default because it
# builds faster and needs no image registry; nothing in the server depends on this file.
#
# Build:  docker build -t kickoff-server .
# Run:    docker run --rm -p 8080:8080 --env-file .env kickoff-server

# ---- deps: production dependency tree, cached separately from the source -----------------
FROM node:22-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

# ---- build: full dependency tree, compiles TypeScript to dist/ ---------------------------
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
# `--include=dev` because npm omits devDependencies whenever NODE_ENV=production, and
# typescript is one: an inherited NODE_ENV would otherwise fail this stage on `tsc: not found`.
RUN npm ci --include=dev
COPY tsconfig.json ./
COPY src ./src
# `npm run build` also copies src/store/schema.sql into dist/ — it is data, so tsc ignores it
# and the Postgres store would find no schema to apply at startup without that step.
RUN npm run build

# ---- runtime: dist + production deps only, nothing that can compile ----------------------
FROM node:22-slim AS runtime
ENV NODE_ENV=production
ENV PORT=8080
WORKDIR /app

# node:22-slim ships an unprivileged `node` user (uid 1000); the image never runs as root.
COPY --from=deps  --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node package.json ./

USER node
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

# `node`, not `npm start`: as PID 1 the server receives SIGTERM directly and runs its own
# shutdown. npm does forward the signal, but only after ~1s and via a second process that
# earns nothing in an image whose only job is to run this one command.
CMD ["node", "dist/index.js"]
