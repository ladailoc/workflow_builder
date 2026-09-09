import { NextRequest, NextResponse } from "next/server";

const BACKEND_BASE =
  process.env.BACKEND_INTERNAL_URL ||
  process.env.NEXT_PUBLIC_API_BASE_URL ||
  "http://localhost:8080";

async function proxyRequest(
  request: NextRequest,
  context: { params: Promise<{ path?: string[] }> },
) {
  const { path } = await context.params;
  const subPath = path && path.length > 0 ? "/" + path.join("/") : "";
  const search = request.nextUrl.search;
  const targetUrl = `${BACKEND_BASE.replace(/\/$/, "")}/api/v1${subPath}${search}`;

  const requestHeaders = new Headers(request.headers);
  requestHeaders.delete("host");
  requestHeaders.delete("connection");

  // If no actor headers were sent by client, supply standard default actor
  if (!requestHeaders.has("x-actor-id")) {
    requestHeaders.set("x-actor-id", "10000000-0000-4000-8000-000000000001");
    requestHeaders.set("x-actor-name", "Alice User");
    requestHeaders.set("x-actor-roles", "USER,DEVELOPER");
    requestHeaders.set(
      "x-actor-permissions",
      "TICKET_CREATE,TICKET_VIEW,TASK_CLAIM,TASK_EXECUTE",
    );
  }

  const method = request.method;
  const body =
    method !== "GET" && method !== "HEAD"
      ? await request.arrayBuffer()
      : undefined;

  try {
    const backendResponse = await fetch(targetUrl, {
      method,
      headers: requestHeaders,
      body,
      redirect: "manual",
    });

    const responseHeaders = new Headers(backendResponse.headers);
    responseHeaders.delete("content-encoding");
    responseHeaders.delete("transfer-encoding");

    const responseBody = await backendResponse.arrayBuffer();

    return new NextResponse(responseBody, {
      status: backendResponse.status,
      statusText: backendResponse.statusText,
      headers: responseHeaders,
    });
  } catch (error) {
    return NextResponse.json(
      {
        type: "urn:workflow-platform:problem:gateway-error",
        title: "Bad Gateway",
        status: 502,
        detail: `Failed to connect to backend at ${targetUrl}: ${error instanceof Error ? error.message : String(error)}`,
      },
      { status: 502 },
    );
  }
}

export const GET = proxyRequest;
export const POST = proxyRequest;
export const PUT = proxyRequest;
export const DELETE = proxyRequest;
export const PATCH = proxyRequest;
export const HEAD = proxyRequest;
export const OPTIONS = proxyRequest;
