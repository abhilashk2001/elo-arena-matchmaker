# syntax=docker/dockerfile:1

# Build stage: install deps and produce the static Vite bundle.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Run stage: serve the static bundle with nginx, which also proxies the API paths to the app
# service so the browser makes same-origin calls (the same arrangement the Vite dev proxy gives).
FROM nginx:1.27-alpine AS run
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
