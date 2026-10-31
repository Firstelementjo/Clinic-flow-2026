export default {
  async fetch(request: Request, env: { ASSETS: { fetch: (req: Request) => Promise<Response> }; API_ORIGIN: string }): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith('/api/')) {
      const target = new URL(url.pathname + url.search, env.API_ORIGIN);
      const proxied = new Request(target, request);
      const response = await fetch(proxied);
      const headers = new Headers(response.headers);
      headers.set('Access-Control-Allow-Origin', url.origin);
      headers.set('Access-Control-Allow-Credentials', 'true');
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers,
      });
    }

    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<{ ASSETS: { fetch: (req: Request) => Promise<Response> }; API_ORIGIN: string }>;
