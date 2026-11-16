// Temporary bulk-upload Worker: wrangler's R2 commands go through the Cloudflare REST API, which answers 429 after a few
// hundred requests, while a Worker writes to R2 through its binding with no such limit.
// Deployed only for the cover upload and deleted afterwards (see tools/books-api/README.md).
// PUT /covers/{small|large}/{id9}.avif  or  /covers/media/{small|large}/{A|M|S + 8 digits}.avif   Authorization: Bearer <secret TOKEN>

export default {
  async fetch(request, env) {
    if (request.method !== "PUT") return new Response("method", { status: 405 });
    // trim(): `wrangler secret put` fed from a PowerShell pipe keeps the trailing newline.
    const token = (env.TOKEN ?? "").trim();
    if (!token || request.headers.get("authorization") !== `Bearer ${token}`) return new Response("denied", { status: 401 });
    const key = new URL(request.url).pathname.slice(1);
    if (!/^covers\/(?:media\/)?(small|large)\/(?:\d{9}|[A-Z]\d{8})\.avif$/.test(key)) return new Response("bad key", { status: 400 });
    await env.BOOKS.put(key, request.body, { httpMetadata: { contentType: "image/avif" } });
    return new Response("ok");
  },
};
