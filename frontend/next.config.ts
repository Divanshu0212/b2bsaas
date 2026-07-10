import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // T6.12: standalone output for a minimal multi-stage Docker image (only the traced
  // server + node_modules needed at runtime, same discipline as the backend's slim JRE).
  output: "standalone",
  // Proxy /api/* to the backend so the browser talks same-origin (cookies + no CORS
  // preflight on every call). Backend base comes from the server-side env.
  async rewrites() {
    const backend = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
    return [{ source: "/api/:path*", destination: `${backend}/:path*` }];
  },
};

export default nextConfig;
