# Travel Planner — AI-Powered Intelligent Travel Planning System

![Java](https://img.shields.io/badge/Java-21-orange)

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green)

![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.2.0-blue)

![Next.js](https://img.shields.io/badge/Next.js-14.2.29-black)

![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)

> A full-stack graduate project that combines **Large Language Models (LLMs)**, **Multi-Agent collaboration**, **Graph-based Workflow Orchestration (StateGraph)**, and **Retrieval-Augmented Generation (RAG)** to deliver personalized, dynamic, and explainable travel planning.

---

## Table of Contents

1. [Project Background](#1-project-background)
2. [Tech Stack & Selection Rationale](#2-tech-stack--selection-rationale)
3. [System Architecture](#3-system-architecture)
4. [Module Breakdown](#4-module-breakdown)
5. [Core Implementation Details](#5-core-implementation-details)
6. [Implementation Principles](#6-implementation-principles)
7. [End-to-End Data Flow](#7-end-to-end-data-flow)
8. [Database Design](#8-database-design)
9. [Deployment & Getting Started](#9-deployment--getting-started)
10. [Usage Guide & Tips](#10-usage-guide--tips)
11. [Project Milestones & Documentation](#11-project-milestones--documentation)
12. [Scripts Reference](#12-scripts-reference)
13. [Troubleshooting & FAQ](#13-troubleshooting--faq)

---

## 1. Project Background

### 1.1 Motivation

Traditional travel planning faces several pain points:

- **Static templates** — generic itineraries that ignore user preferences, budget, and constraints.
- **Information fragmentation** — scattered attraction data, weather, budgets, and routing logic with no unified intelligence.
- **One-shot generation** — a single prompt produces an itinerary with no ability to iterate, correct, or explain itself.
- **Hallucination risk** — LLMs freely "invent" attractions, distances, and prices without grounding in real data.

### 1.2 What This Project Provides

The system addresses these issues through an **agentic + RAG + workflow** design:

| Capability                 | How It Is Achieved                                                                            |
| -------------------------- | --------------------------------------------------------------------------------------------- |
| **Personalization**        | User profile + per-turn preference tags + multi-round dialogue → budget-aware planning       |
| **Factuality / Grounding** | RAG over a curated attraction knowledge base (Elasticsearch + Milvus vector search)           |
| **Iterative planning**     | Multi-agent collaboration (supervisor + specialized sub-agents) with chat-driven REFINE       |
| **Controllable workflow**  | Spring AI Alibaba **StateGraph** — every step is a graph node with explicit state transitions |
| **Explainability**         | Agent trace recording (each agent step, tool call, and reasoning is persisted)                |
| **Safety**                 | Prompt injection guard, rate limiting, and circuit breaking at the service layer              |

### 1.3 Development Context

This is a **personal thesis project** developed iteratively under a milestone-driven workflow (M0 → M28), with **F1–F124 bug-fix series + M3 ten-phase optimization (MessagePipeline / dependency sinking / prompt externalization / etc.) + M4 three-direction optimization (agent session compression / RAG reliability / state recovery) + M5 frontend experience optimization + M6 streaming & lifecycle hardening (SSE streaming, dual MVC/WebFlux transport, turn cancellation with stop/retry, itinerary resume fixes, external-review-driven architecture refactor) + M7–M15 (model gateway, retrieval reliability, engineering close-out, business extensions, map/quota/weather) + M16–M28 (config single-source, internal-token security, session anchoring, per-turn preference tags, chat itinerary writeback with version snapshots, and seventeen rounds of live-test hardening)**. Every milestone and fix is documented in `docs/business-records/` (390 markdown files), following a strict "design → implement → self-review" discipline.

---

## 2. Tech Stack & Selection Rationale

### 2.1 Backend

| Technology                              | Version               | Purpose & Why Chosen                                                                                                |
| --------------------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------------------- |
| **Java**                                | 21                    | Modern LTS, virtual threads, records, pattern matching                                                              |
| **Spring Boot**                         | 3.5.0                 | Foundation for auto-config, DI, REST, embedded server                                                               |
| **Spring Cloud / Spring Cloud Alibaba** | 2024.0.0 / 2023.0.1.0 | Microservice ecosystem readiness (Nacos, Sentinel integration path)                                                 |
| **Spring AI**                           | 1.1.2                 | Unified LLM abstraction (`ChatClient`, `ChatModel`)                                                                 |
| **Spring AI Alibaba**                   | 1.1.2.0               | **Agent Framework** (supervisor agents) + **Graph Core** (StateGraph workflow) — the core AI orchestration backbone |
| **Spring AI Alibaba Starter DashScope** | 1.1.2.0               | Connects to Alibaba Cloud DashScope (Qwen LLM series)                                                               |
| **Spring WebFlux / Reactor**            | 6.2.x / 3.7.x         | Reactive transport pilot (`travel-stream-webflux` :8083) and the `Flux<StreamEvent>` streaming pipeline (M6)        |
| **MyBatis-Plus**                        | 3.5.7                 | ORM with rich CRUD, pagination, and codegen support                                                                 |
| **Redisson**                            | 3.28.0                | Reserved for distributed locks/rate limiting: version-managed only in the parent POM since M3-19; not enabled (P3 evolution item) |
| **Spring Data Redis**                   | 3.5.0                 | Redis read/write for refresh tokens and session summaries (explicitly introduced in planning since M3-19)                 |
| **Milvus SDK**                          | 2.3.4                 | Vector database client for semantic similarity search                                                               |
| **MinIO**                               | 8.5.7                 | S3-compatible object storage for crawled data & avatars                                                             |
| **Elasticsearch**                       | 7.17.18               | Full-text search over the attraction knowledge base                                                                 |
| **POI / PDFBox**                        | 5.2.5 / 3.0.1         | Document parsing in the ETL pipeline (Excel, PDF)                                                                   |
| **Hutool**                              | 5.8.28                | Utility library (HTTP, crypto, date, file)                                                                          |
| **Knife4j**                             | 4.5.0                 | Removed (M3-19 dependency sinking); API contract doc: `docs/test/backend-api-postman-testing-2026-08-23.md`                 |
| **JJWT**                                | 0.12.6                | JWT-based stateless authentication                                                                                  |

### 2.2 Frontend

| Technology                       | Version              | Purpose & Why Chosen                                      |
| -------------------------------- | -------------------- | --------------------------------------------------------- |
| **Next.js**                      | 14.2.29 (App Router) | SSR/CSR hybrid, file-based routing, API routes            |
| **React**                        | 18.3.1               | UI component model                                        |
| **TypeScript**                   | 5.5.4                | Type safety across the frontend                           |
| **Tailwind CSS**                 | 3.4.13               | Utility-first styling, dark/light theme via `next-themes` |
| **Zustand-like state via hooks** | —                    | Lightweight client state with React hooks                 |
| **react-hook-form + zod**        | 7.53 / 3.23          | Form validation with schema-first types                   |
| **axios**                        | 1.7.7                | HTTP client for backend APIs                              |
| **react-markdown**               | 10.1.0               | Render LLM markdown output (itinerary cards, chat)        |
| **markmap-lib / markmap-view**   | 0.18.10              | Convert itinerary JSON into interactive **mind maps**     |
| **recharts**                     | 2.12.7               | Budget breakdown charts                                   |
| **sonner**                       | 1.5.0                | Toast notifications                                       |

### 2.3 Middleware (Docker Compose)

| Service                | Image                   | Port(s)      | Role                                                 |
| ---------------------- | ----------------------- | ------------ | ---------------------------------------------------- |
| **MySQL**              | mysql:8.0               | 3306         | Business data (users, itineraries, memories, traces) |
| **Redis**              | redis:7-alpine          | 6379         | Session cache, rate limit counters, hot data         |
| **Elasticsearch**      | 7.17.18 (+ IK analyzer) | 9200 / 9300  | Full-text search index for attractions               |
| **Kibana**             | 7.17.18                 | 5601         | ES visualization & debugging                         |
| **etcd**               | latest                  | 2379         | Milvus dependency (metadata)                         |
| **Milvus**             | milvus:latest           | 19530 / 9091 | Vector database (semantic embeddings)                |
| **MinIO (for Milvus)** | minio:latest            | 9000 / 9001  | Milvus storage backend                               |
| **MinIO (business)**   | minio:latest            | 9000 / 9001  | Project file storage (avatars, crawled data, docs)   |

### 2.4 LLM Provider

- **Multi-model gateway (M7)**: `travel-ai-gateway` provides a model registry
  (`travel.ai.model-registry.*`), a `ChatModel` factory (DashScope native /
  OpenAI-compatible endpoints) and `RoleRoutingChatModel` proxies registered as
  `chatModel` (@Primary, main role) and `lightModel` (light role).
- **Runtime model selection**: chat and plan pages let users pick a model; the
  request carries `model`, validated at the entry (unknown/disabled → `40005
  MODEL_NOT_FOUND`). `GET /api/v1/models` exposes the selectable registry list.
- **Roles**: user-selected models only override the **main** role; auxiliary calls
  (intent classification / preference extraction / budget / RAG cheap judgments)
  stay on the light default (`qwen-turbo`).
- **Providers**: DashScope (Qwen series, native or compatible mode) and
  OpenAI-compatible endpoints (DeepSeek / GLM). Keys are referenced by
  environment-variable name in the registry (`DASHSCOPE_API_KEY`,
  `DEEPSEEK_API_KEY`, `GLM_API_KEY`); see `.env.example`.
- **Fallback switch**: `travel.ai.model-registry.enabled=false` (or missing)
  falls back to legacy DashScope-only `chatModel`/`lightModel` beans.
- **Reliability hardening (M7-6/7-8)**: light-role call sites are explicit
  (`@Qualifier("lightModel")`, default `qwen-turbo`), agent-trace records the actually
  routed model, and LLM-extracted structures are validated against the raw query
  (type must be supported by the query text; keywords are anchored to it) before
  filtering or caching. The supervisor main agent routes through `RoutingChatClient`,
  which normalizes "prose + trailing JSON array" replies to the pure array the graph
  framework requires, so the 4-sub-agent pipeline is never skipped by formatting drift.

---

## 3. System Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                         Frontend (Next.js 14)                      │
│   login / register · chat · plan · itinerary · profile · attractions│
│   react-markdown · markmap (mind map) · recharts (budget charts)    │
└───────────────┬────────────────────────────────────────────────────┘
                │  HTTP / JSON (JWT Authorization)
┌───────────────▼────────────────────────────────────────────────────┐
│                    travel-planning  (:8081)                        │
│  ┌───────────────┐  ┌─────────────────────────────────────────┐    │
│  │ Controllers   │  │         Multi-Agent Framework           │    │
│  │ Auth/Chat/    │  │  TravelSupervisorAgent (orchestrator)   │    │
│  │ Itinerary/    │  │    ├─ AttractionAgent                    │    │
│  │ Avatar        │  │    ├─ RouteAgent                         │    │
│  └───────┬───────┘  │    ├─ BudgetAgent                        │    │
│          │          │    └─ PreferenceAgent                    │    │
│          │          │  StateGraph (TravelWorkflowBuilder)      │    │
│          │          │  Understand→Retrieve→Plan→Budget→Output    │    │
│          │          └─────────────────────────────────────────┘    │
│          │  9-Step MsgPipeline (Guard→…→Persist)                   │
│          │  Idem · Session Close/Finalize · Resume                 │
│          │  3-Layer Memory · Guard · Trace                         │
│          │  Session Anchoring (E1) · Preference Tags (E4)          │
└──────────┼──────────┬──────────────────────┬──────────────────────┘
           │          │                      │
┌──────────▼─────────┐│ ┌───────────────────▼──────────────────────┐
│   travel-knowledge ││ │          travel-crawl  (:8087)           │
│        (:8082)     ││ │  jsoup crawler · file queue · MinIO      │
│  ETL Pipeline      ││ │  schedule jobs · PipelinePublisher       │
│  (POI/PDF/HTML→     ││ └───────────────────┬────────────────────┘
│   chunks→embeddings)││                     │
│  RagDispatcher     ││                     ▼
│  ├ NaiveRag        ││        Elasticsearch (full-text index)
│  ├ HybridRag       ││        Milvus (vector index) ← MinIO
│  ├ SelfRag         ││        MySQL (source data)
│  └ CorrectiveRag   ││        Web search enrichment (Tavily/MCP, quota'd)
│  QueryUnderstanding│└── travel-core/common (kernel + shared DTOs)
│  RAG Eval/Judge/   │
│  Rerank/ParentCtx  │
└────────────────────┘
```

**Model gateway (M7)** — `travel-ai-gateway` owns the model registry
(`travel.ai.model-registry.*`) and exposes `chatModel` (@Primary, main role) and
`lightModel` proxies that every LLM call point injects. The registry is the single
source of truth for providers, roles, and selectable models; request-level `model`
is threaded from the controller DTOs into `ChatService.runStream` / itinerary
generation, and `travel.ai.model-registry.enabled=false` restores the legacy
DashScope-only beans.

Streaming transport (M6) — the chat pipeline is transport-agnostic:

```
                        ┌──────────────────────────────────────────────┐
                        │          travel-chat-stream (shared)         │
                        │  ChatStreamService → Flux<StreamEvent>       │
                        │  events: thinking / token / done / error / id│
                        └───────────────┬──────────────────────────────┘
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              ▼                                                 ▼
┌───────────────────────────┐                  ┌──────────────────────────────┐
│  MVC path — travel-       │                  │  Reactive path — travel-      │
│  planning (:8081)         │                  │  stream-webflux (:8083,       │
│  ChatController →         │                  │  optional gray switch)        │
│  SseStreamAdapter →       │                  │  ReactiveJwtAuthFilter →       │
│  SseEmitter               │                  │  ChatStreamWebfluxController  │
└─────────────┬─────────────┘                  └───────────────┬──────────────┘
              │                                                │
              │   internal HTTP bridge (X-Internal-Token)      │
              └──────────────▶ brief / weather / anchors ◀─────┘
                               SSE text/event-stream
                               frontend: NEXT_PUBLIC_STREAM_BASE
```

---

## 4. Module Breakdown

The backend is a **Maven multi-module** project (`pom.xml`, Java 21). Ten backend modules + one frontend app:

```
travel-planner/
├── pom.xml                 # Parent POM (dependency & version management)
├── docker-compose.yml      # All middleware services
├── travel-core/            # Pure-Java shared kernel (circuit breaker/rate limiter/RRFusion/normalizers)
├── travel-common/          # Shared entities, DTOs, enums, utils, exceptions
├── travel-ai-gateway/      # Model registry + factory + routing proxy (M7)
├── travel-chat-stream/     # Transport-agnostic chat streaming pipeline (Flux<StreamEvent>, M6)
├── travel-chat-domain/     # Chat domain: 9-step pipeline / agents / memory / guards / trace (M6-31)
├── travel-web-mvc/         # MVC cross-cutting: global exception, rate-limit interceptor, SseStreamAdapter
├── travel-stream-webflux/  # Reactive transport (:8083): JWT filter + ChatStream WebFlux controller (M6-30)
├── travel-crawl/           # Web crawler for attraction data
├── travel-knowledge/       # ETL + RAG knowledge engine
├── travel-planning/        # Core agentic planning service (main app, :8081)
└── travel-frontend/
    └── next-app/           # Next.js 14 frontend
```

### 4.1 travel-common

Shared library, no business logic. Contains:

- **Entities & DTOs** — user, itinerary, attraction, chat message, memory models.
- **Enums** — itinerary status, RAG strategy types, memory types, error codes.
- **Utils** — JSON, date, crypto (SM4/AES), string, geo-distance helpers.
- **Common exceptions & response wrappers** — unified `Result<T>` envelope.

### 4.2 travel-ai-gateway

Model routing gateway (M7), the single source of truth for LLM providers:

- **`ModelRegistry`** — binds `travel.ai.model-registry.*`; startup validation
  (unique keys, missing `api-key-env` → disabled + WARN, role defaults, selectable
  filtering); exposes `GET /api/v1/models` through `ModelController` in planning.
- **`ChatModelFactory`** — builds models per descriptor (DashScope native /
  OpenAI-compatible) with `computeIfAbsent` single-flight caching and per-model
  timeout injection.
- **`RoleRoutingChatModel`** — `ChatModel` proxy: request-level model >
  `ModelRoutingContext.current()` (main role only) > registry role default;
  unknown/disabled/not-selectable fails fast with `40005 MODEL_NOT_FOUND`.
- **`RoutingChatClient`** (M7-8) — wraps the main agent's `ChatClient` and
  normalizes "prose + trailing JSON array" replies to the pure routing array at the
  `chatResponse()` exit, so the Supervisor graph never loses its sub-agent pipeline
  to formatting drift.
- **Rollback** — `travel.ai.model-registry.enabled=false` restores the legacy
  DashScope-only beans (`LegacyModelFallbackConfig`).

### 4.3 travel-crawl (`:8087`)

Attraction data ingestion pipeline:

- **Crawler service** (`CrawlService`) — jsoup-based web scraping of attraction POIs.
- **File queue & store** — downloaded assets (images, docs) persisted to MinIO.
- **PipelinePublisher** — publishes crawl results downstream (to knowledge module).
- **Scheduled jobs** — periodic re-crawl / incremental updates.

### 4.4 travel-knowledge (`:8082`)

The **knowledge engine** — makes the LLM grounded and factual:

- **ETL Pipeline** (`etl/`) — extract from raw sources (HTML/Excel/PDF via POI & PDFBox), clean, chunk, embed, and index into **Elasticsearch** (full-text) + **Milvus** (vectors).
- **RagDispatcher** — runtime dispatcher that selects a RAG strategy per query.
- **RAG Strategies** (`rag/strategy/`, template-method `AbstractRagStrategy` + decorators, M3-4):
  - `NaiveRagStrategy` — top-k vector similarity retrieval.
  - `HybridRagStrategy` — fused ES keyword + Milvus vector scores.
  - `SelfRagStrategy` — self-reflection loop: generate → critique → regenerate if needed.
  - `CorrectiveRagStrategy` — query rewriting / fallback retrieval when the first pass is poor.
- **QueryUnderstanding** — classifies intent & entities, extracts constraints (budget, days, companions) before retrieval.
- **Hallucination guards (M7-8)** — LLM-extracted `type` must be supported by the
  raw query (config keywords + synonym table) and `keywords` are anchored to the
  query text and de-duplicated; validated intents are cached, never raw LLM output.
- **Zero-result fallback (M7-8)** — when a type-filtered retrieval returns empty,
  `AbstractRagStrategy` retries once with `city` only (type dropped) and logs a WARN.
- **Quantified RAG evaluation** (M4-2) — 45 golden queries, Recall@5/MRR@5 hard gates + LLM soft gates, `run_rag_eval.ps1`;
- **Rerank SPI / online Judge / parent-context fetch** (M4-5/6) — Rerank defaults to noop, Judge off by default, deterministic by-prefix parent fetch;
- **Web-search enrichment** (M8-4/5, M9-2) — a provider registry (Tavily → MCP →
  Noop) fills only locally-missing `openHours`/`ticketPrice`, labels them
  `web_enrich` with a low-confidence notice, and writes results back into NULL
  columns with a 7-day debounce followed by incremental ETL.
- **Controllers** — `AttractionController` (POI CRUD/search), `EtlController` (pipeline trigger), `RagController` (retrieval debug), `FileController` (MinIO upload/download), `MemoryController` (session-context search/by-prefix), `FileAccessController` (proxy/presign/resolve + rate limit 429).

### 4.5 travel-chat-stream

Transport-agnostic chat streaming domain (M6-6-R1 Step 0):

- `ChatStreamService` / `ChatStreamProperties` — stream lifecycle and timeout configuration;
- `StreamingPipeline` — turns the chat-domain reply into a `Flux<StreamEvent>` (thinking / token / done / error / id);
- `StreamEvent` / `StreamErrorCode` — shared wire protocol consumed by both the MVC and WebFlux adapters;
- `TurnCancellation` / `TurnInterruptedException` — cancellation-token primitives shared by all transports.

### 4.6 travel-chat-domain

Chat domain, independent of any web transport (M6-31, ChatService sink-down):

- **9-step message pipeline** — Guard → Persistence → Preference → Knowledge → Intent → Memory → Budget → Routing → Reply persist;
- **Agent layer** — `TravelSupervisorAgent` facade (M6-58 T9 split) delegating to `DirectAnswerExecutor` / `SupervisorGraphExecutor` / `SupervisorStreamExecutor`, plus `ChatIntentClassifier`, `PlanningHeuristics`, `TokenUsageInterceptor`;
- **Memory** — short-term / long-term / chat / knowledge / session-store, with
  rolling-session summaries (max-turns / refresh-turns / recent-window /
  input-max-tokens four-tier fallback) keeping token injection bounded (M28-8);
- **Session anchoring & preference tags** — `SessionAnchorStore`
  (`t_chat_session.anchored_itinerary_ids`) renders the anchored-itinerary prompt
  section; `PreferenceSectionRenderer` renders the per-turn preference-constraint
  section with deterministic destination-conflict hint lines (M23/M23b);
- **Session-fact consensus** (`SessionFactConsolidator`) — merges constraint /
  feedback slices per topic, latest wins, one statement may update several topics,
  values canonicalized (M28-10);
- **Guards & trace** — PromptGuard, rate limiter, circuit breaker, per-request agent trace;
- **Runtime reliability (M7-8)** — `TraceAspect` covers `ChatService.runStream` so
  the per-request message snapshot ThreadLocal is cleared on the SSE/WebFlux path
  (no cross-request history leakage); Redis commands interrupted by thread
  cancellation are treated as turn cancellation (`INTERRUPTED`, resumable) instead of
  generic `FAILED`; rolling-summary validation checks the full generation input
  (old summary + new messages).

### 4.7 travel-web-mvc

MVC-only cross-cutting components (M6-9 P2):

- `GlobalExceptionHandler` — transport-safe error handling (avoids writing JSON bodies into `text/event-stream`);
- `RateLimitInterceptor` — MVC rate limiting;
- `SseStreamAdapter` — `Flux<StreamEvent>` → Spring `SseEmitter` (keepalive,
  cancellation, graceful SSE-disconnect handling; after a broken response it skips
  `complete()` and just disposes, M7-8).

### 4.8 travel-stream-webflux (`:8083`)

Reactive transport pilot (M6-30~35):

- `ReactiveJwtAuthFilter` — JWT-only user resolution (the `X-User-Id` header fallback was removed, M6-57 T8);
- `ChatStreamWebfluxController` — reactive SSE endpoint backed by the same chat domain,
  threading `preferences` / `anchoredItineraryIds` from the request body into
  `StreamRequest.attributes` (M28-12);
- `WebfluxChatSupportBridge` — internal HTTP bridge to planning (:8081) for
  itinerary briefs / weather context, authenticated with the shared
  `X-Internal-Token` (M26-2);
- Global WebFlux exception handling + CORS; the frontend gray-switches to it with `NEXT_PUBLIC_STREAM_BASE=http://localhost:8083`.
- `StreamBeansConfig` — explicit beans for the pilot (JWT auth, `StreamMetrics` Noop
  fallback via `@ConditionalOnMissingBean`, Feign `HttpMessageConverters`, pilot
  executor); imports `GatewayAutoConfig` so the same model registry serves 8083.

### 4.9 travel-planning (`:8081`)

The **core service** where agents collaborate:

- **Controllers** — `AuthController` (register/login/JWT), `ChatController` (multi-turn dialogue + close + idempotency clientMessageId + SSE stream endpoint `POST /api/v1/chat/sessions/{sessionId}/messages/stream` + system-note endpoint), `ItineraryController` (generate/resume/view/list/delete + SSE stream + version list/detail/switch + `PATCH /{id}/title` rename + `PATCH /{id}/constraints` preference metadata + `GET /{id}/export.ics` calendar export + map-routes), `MeController` (`/users/me`), `AvatarController` (user avatar upload via MinIO), `SessionAnchorController` (anchor get/replace), admin reliability endpoints.
- **Multi-Agent Framework** (`agent/`):
  - `TravelSupervisorAgent` — facade that routes user intent to the right path (M6-58: implementation split into `DirectAnswerExecutor` / `SupervisorGraphExecutor` / `SupervisorStreamExecutor`).
  - `AttractionAgent` — attraction recommendation grounded in RAG.
  - `RouteAgent` — day-by-day route & sequencing.
  - `BudgetAgent` — cost estimation & breakdown.
  - `PreferenceAgent` — preference extraction & profile update.
- **Workflow** (`workflow/`) — `TravelWorkflowBuilder` assembles a **StateGraph**: `[Query Understanding] → [Retrieval] → [Planning] → [Budget] → [Output]` with explicit state transitions and an in-graph deterministic conflict-check retry loop (M8-3).
- **9-step message pipeline** (M3-8~18, implemented in `travel-chat-domain`) — Guard→Persistence→Preference→Knowledge→Intent→Memory→Budget→Routing→Reply persist, each step independently testable; ChatService reduced to pure orchestration.
- **Streaming & turn cancellation** (M6) — SSE streaming (thinking/token/done events, `Last-Event-ID` replay), `TurnCancellation` chain (interceptor short-circuit + root-cause unwrapping), stop/retry endpoints, Redis pub/sub cancellation broadcast.
- **Message idempotency & session finalization** (M4-3/4) — `t_chat_message_idem` (PENDING/COMPLETED/FAILED), close state machine (ARCHIVED rejects writes 40902, summary finalize + Lua CAS atomic write).
- **Itinerary state machine & resume** (M4-7/8/9 + M6-51/53/54) — GENERATING/GENERATED/FAILED + node snapshots + prefix-subgraph cache + `resume` endpoint; generation runs on an independent virtual thread (SSE disconnect stops only the push), and the list auto-polls while any row is GENERATING.
- **Chat itinerary writeback** (M13/M26–M28) — chat PLANNING creates a first-class
  itinerary (create-on-chat); REFINE rewrites it with a deterministic version diff;
  constraint columns (budget/days/start_date/party/interests) are updated from
  explicitly extracted user input so profile text can never pollute parsing;
  `t_itinerary_version` snapshots the constraints at write time so version
  switching restores the exact era (M28-7).
- **Memory System** (`memory/`) — three layers:
  - `shortterm` — session-scoped working memory (current trip context).
  - `longterm` — cross-session user preferences & facts (plus behavior profile, M17).
  - `chat` — conversation history memory.
  - `knowledge` — knowledge-oriented memory (`KnowledgeRetrievalService`) bridging to the knowledge module.
  - `sessionstore` — Redis-backed session store.
- **Guard Layer** (`guard/`) — `PromptGuard` (prompt injection detection), rate limiting, and circuit breaker protecting LLM calls.
- **Trace** (`trace/`) — per-request agent trace: every node execution, tool call, and LLM exchange is recorded for explainability and debugging.

### 4.10 travel-frontend (`next-app`)

Next.js 14 App Router application (`npm run dev` serves :3000; `npm run dev:alt`
serves :3100 — the port used throughout development):

- **Routes**: `/login`, `/register`, `/chat` (AI dialogue + SSE streaming + anchor
  panel + per-turn preference panel), `/plan` (plan creation), `/itinerary` (list)
  and `/itinerary/[id]` (detail: version dialog, AMap road-network map, ICS export,
  double-click title rename), `/profile` (user settings), `/attractions` (browse POI
  knowledge base), `/admin/reliability` (ops dashboard with token/quota panels).
- **Key components**:
  - `markmap-view.tsx` — interactive itinerary mind map.
  - `theme-provider.tsx` / `theme-toggle.tsx` — dark/light theme.
  - `prefetch-provider.tsx` — route prefetch optimization.
  - `components/chat/` — `SessionList` / `MessageBubble` (system-role centered
    notices) / `chat-page-content.tsx` (full page content, kept out of the
    "use client" entry file so IDE serializable-props inspections stay clean, M28-17).
  - `hooks/useChatStream.ts` — module-level stream store: client-side route switches
    only unsubscribe, thinking continues in the background, and away results are
    consumed on return; `useSessionAnchor` (GET-generation guarded loads),
    `useSessionPreference` (per-session localStorage tags with functional merges).
  - `components/feature/` — `ExportIcsButton`, `itinerary-version-dialog`
    (switch + preference sync), `ItineraryMap` (AMap tiles, day-grouped routes).
  - `lib/schemas.ts` — pure functions (`mergePreferenceSync`, `preferenceTagTexts`,
    `titleNeedsSave`) covered by Vitest.
  - `ui/` — reusable UI primitives (paged dropdowns for large option sets).
- **Data layer**: `lib/` axios client with JWT interceptor; typed API functions per domain.

---

## 5. Core Implementation Details

### 5.1 Multi-Agent Collaboration (Spring AI Alibaba Agent Framework)

The `TravelSupervisorAgent` acts as a **router + coordinator**:

1. Receives the user message and current memory context.
2. Classifies intent (attraction inquiry / route planning / budget / preference update / general chat).
3. Delegates to the appropriate sub-agent (or a chain of agents).
4. Aggregates sub-agent outputs into a coherent response.

Each sub-agent is a Spring AI **@Agent** with its own system prompt, tools (e.g. RAG search, map/distance tool), and guardrails.

> **M3-7/M3-20 evolution**: four sub-agents unified under the `AbstractReactSubAgent` template
> (abstract `name/model/systemPrompt/instruction/outputKey/tools` + `@PostConstruct` assembly);
> all 18 prompts externalized to `resources/prompts/*.st` (`PromptTemplates` lazy loading + versioning).

### 5.2 StateGraph Workflow Orchestration

`TravelWorkflowBuilder` (Spring AI Alibaba **Graph Core**) defines the planning pipeline as a directed graph:

```
             ┌───────────┐
   user ───▶ │ Understand │  intent + constraints extraction
             └─────┬─────┘
                   ▼
             ┌───────────┐
             │  Retrieve  │  RAG → grounded context
             └─────┬─────┘
                   ▼
             ┌───────────┐
             │  Plan      │  day-by-day itinerary assembly
             └─────┬─────┘
                   ▼
             ┌───────────┐
             │  Budget    │  cost estimation & breakdown
             └─────┬─────┘
                   ▼
             ┌───────────┐
             │  Output    │  structured JSON + markdown
             └───────────┘
```

Benefits of the graph model:

- **Deterministic control flow** with explicit state at each node.
- **Conditional edges** — e.g. budget node can trigger a replan loop if over budget.
- **Observability** — every node's input/output is captured for tracing.
- **Extensibility** — new nodes/edges can be added without rewriting the pipeline.

> **M3-9/M4-8 evolution**: `TravelWorkflowBuilder` precompiles and caches the `CompiledGraph`
> (immutable reuse); itinerary recovery uses **prefix-subgraph caching** (the `full` key is
> line-for-line identical to the baseline; `from:preference/attraction/route/budget` resume
> breakpoints), plus `SnapshotNodeWrapper` node-level snapshots and the `resume` endpoint.

### 5.3 RAG Pipeline (Four Strategies)

`RagDispatcher` selects a strategy based on query type and retrieval confidence:

| Strategy           | Mechanism                                                   | Use Case                                          |
| ------------------ | ----------------------------------------------------------- | ------------------------------------------------- |
| **Naive RAG**      | Embed query → Milvus top-k → prompt context                 | Simple factual attraction questions               |
| **Hybrid RAG**     | ES BM25 + Milvus vector scores fused (RRF)                  | Broad questions needing keyword + semantic match  |
| **Self-RAG**       | Generate → self-critique → regenerate if insufficient       | Questions where initial answers may be incomplete |
| **Corrective RAG** | Evaluate retrieval quality → rewrite query or switch source | Low-confidence retrievals                         |

The retrieval context is **pinned to the prompt with source attribution**, and the agent is instructed to answer *only* from the provided context or explicitly say it does not know — reducing hallucination.

> **M3-4/M4-1b/M4-2/M4-5/6 evolution**: strategy layer templated (`AbstractRagStrategy`) +
> decorators; session-knowledge and attraction RRF merged into `RRFusion.fuseGeneric`; topK
> configurable (`travel.rag.session-context.top-k=8` / `attraction-candidates.top-k=5`);
> added RAG offline evaluation (Recall@5/MRR@5 hard gates), online relevance Judge (off by
> default), Rerank SPI (noop default), and session-knowledge parent-context fetch (deterministic
> by-prefix query).

### 5.4 ETL Pipeline

```
Raw data (HTML / Excel / PDF / CSV)
   │  extract (jsoup / POI / PDFBox)
   ▼
Cleaned text
   │  chunk (semantic-aware splitting)
   ▼
Chunks
   ├──▶ Elasticsearch  (full-text index, IK analyzer for Chinese)
   └──▶ Milvus         (dense embeddings via embedding model)
          ▲
        MinIO (source files backup)
```

Triggered via `EtlController` (manual) or scheduled jobs, with idempotent re-runs.

### 5.5 Three-Layer Memory System

| Layer           | Scope                  | Storage     | Purpose                                                                 |
| --------------- | ---------------------- | ----------- | ----------------------------------------------------------------------- |
| **Short-term**  | Current session / trip | Redis       | Working context: destination, dates, companions, current plan draft     |
| **Long-term**   | Across sessions        | MySQL       | User profile: preferences, dietary needs, budget habits, visited places |
| **Chat memory** | Conversation history   | Redis/MySQL | Multi-turn dialogue coherence & follow-up questions                     |

Memory is **explicitly injected** into the supervisor's context window, and updated by `PreferenceAgent` after each meaningful exchange.

> **M4-1/M4-4 evolution**: session summary writes switched to **Lua CAS atomic writes**
> (summary/meta dual keys + version compare, larger version wins on conflict); session close
> triggers a **full finalize summary** (`summaryType=final`, without concatenating the old
> summary), using the `summary_final` column as the implicit pending item with startup compensation.
>
> **M28-8 evolution**: token-bounded injection — rolling summary from `summary-min-turns`,
> refreshed every `summary-refresh-turns`, a recent window of 2 full turns, and an
> `input-max-tokens` hard cap with a four-tier fallback (summary replace → truncated summary
> → tightened profile → conservative reject-save). Observed turn-over-turn token injection
> drops once the summary takes over.

### 5.6 Security & Guardrails

- **PromptGuard** — detects prompt-injection patterns and jailbreak attempts before they reach the LLM.
- **Rate Limiter** — Redis/Redisson-based token-bucket rate limiting per user/IP on chat & RAG endpoints.
- **Circuit Breaker** — protects the LLM provider (DashScope) from cascading failures; falls back to cached responses.
- **JWT Auth** — stateless token auth; passwords stored hashed.
- **JWT-only identity on the reactive transport** — `ReactiveJwtAuthFilter`; the `X-User-Id` header fallback was removed (M6-57 T8).
- **Internal-token auth** — inter-service HTTP bridges (webflux → planning) require the
  shared `X-Internal-Token` secret and fail closed when unset (M21).
- **Input validation** — zod (frontend) + Bean Validation (backend).

### 5.7 Agent Trace (Explainability)

Each planning request produces a **trace record**: node ID, agent invoked, tool calls, prompt/response snapshot, timestamps, and token usage. This powers:

- Debugging complex agent behavior.
- Showing the user *why* a recommendation was made.
- Regression testing of the pipeline (see `scripts/regression/`).

### 5.8 Message Idempotency & Session Lifecycle (M4-3/4)

- Message idempotency: `t_chat_message_idem` (PENDING/COMPLETED/FAILED); the check point is
  **before the user message is persisted** and shares a transaction with PENDING registration;
  COMPLETED replays, PENDING returns 40904, FAILED re-runs reusing the original message,
  fallback replies are marked FAILED;
- Session close: `POST /api/v1/chat/sessions/{id}/close` (explicit button only, no beforeunload);
  ARCHIVED rejects writes with 40902, history stays readable, COMPLETED replay is exempt;
- Frontend: UUID idempotency key + 40904 dual-format backoff retry (3s × 4 attempts).

### 5.9 Itinerary State Machine & Resume (M4-7/8/9)

- Three-state machine: `GENERATING → GENERATED | FAILED` (PARTIAL dropped; the snapshot table
  itself is the observable fact of partial completion);
- Node snapshots: `t_itinerary_task_snapshot` + `SnapshotNodeWrapper`
  (Optional/AssistantMessage normalization, GraphResponse rejection);
- Recovery: `POST /api/v1/itineraries/{id}/resume` (guards 40302/40401/40903/40905; zombie
  GENERATING older than 10 minutes is resumable); pre-fixes: idempotency scoped by userId,
  DuplicateKey converted to re-read, transaction self-invocation split out.
- M6-51: conditional-edge completion fix (`budget_estimation → snapshot_budget`) and resume
  context injection (`buildResumeMessage` restores user input + real snapshot sections);
- M6-53/54: generation moved to an independent virtual thread so an SSE disconnect only stops
  the push (no more refresh-induced FAILED); the itinerary list auto-polls every 3s while any
  row is GENERATING.

### 5.10 Quantified RAG Evaluation (M4-2)

- 45 golden queries (docId = MySQL auto-increment primary key string; canonical seed ids
  1=Forbidden City, 6=The Bund, 9=Terracotta Army Museum);
- Hard gates Recall@5/MRR@5 ≥ baseline −2pp (LLM-free); soft gates report relevance/faithfulness;
- `run_rag_eval.ps1` standalone orchestration, `--no-llm` resilience, data-drift RELABEL_HINT;
- Post-integration baseline (2026-08-23): Recall@5≈0.80 / MRR@5≈0.80; refreshed to
  **0.8685 / 0.8741** after the full 799-row re-embedding (M9-1b).

### 5.11 Streaming Transport & Turn Cancellation (M6)

- **Dual transport**: chat SSE is served by MVC (`SseEmitter`, :8081) and WebFlux (:8083);
  both consume the same `travel-chat-stream` `Flux<StreamEvent>` (thinking / token / done /
  error / id). The frontend gray-switches with `NEXT_PUBLIC_STREAM_BASE`.
- **Frontend reveal**: per-session `streamStates` + a 24 ms × 3-char reveal queue; switching
  sessions never aborts the backend stream; background sessions accumulate and notify via a
  red dot on the session list (M6-5 / M6-48 / M6-49).
- **Reconnect & replay**: SSE `Last-Event-ID` + the same idempotency key resume a disconnected
  stream (M6-10).
- **Cancellation chain** (M6-40~46): a `TurnCancellation` token is checked at every node
  boundary; cancellation metadata travels inside the graph `RunnableConfig`; interceptors
  short-circuit, `ReactiveBlockSupport` disposes the Reactor subscription, and root causes are
  unwrapped so `TurnInterruptedException` is never swallowed into fallback text; Redis pub/sub
  broadcasts stop events across instances.
- **Stop / retry UX** (M6-36/47): the frontend stop button calls the interrupt endpoint
  (PENDING → INTERRUPTED), shows an "Execution interrupted" bubble + retry; after a browser
  refresh, the backend `getLatestInterruptedTurn` restores the resume entry.

### 5.12 External-Review-Driven Architecture Refactor (M6-55~58)

An external code review produced two architecture reports; the project adopted them
dialectically (24 items triaged, some corrected after verification):

- **Batch 1 (M6-56)** — test re-homing across `travel-chat-domain` / `travel-chat-stream` /
  `travel-planning` (39 test classes), turn-state transition table + INTERRUPTED idempotency
  tests, rate-limiter scheduled cleanup;
- **Batch 2 (M6-57)** — `ChatService.requireOwnedSession` consolidation, prompt/code-fence
  utility reuse, SHA-256 intent-cache keys, WebFlux JWT-only user resolution;
- **Batch 3 (M6-58)** — `TravelSupervisorAgent` split (facade <300 lines + `PlanningHeuristics`,
  `ReactiveBlockSupport`, `DirectAnswerExecutor`, `SupervisorGraphExecutor`,
  `SupervisorStreamExecutor`, `SupervisorResponseSupport`, `SupervisorTraceSupport`) and the
  chat page split (`components/chat/*` + `hooks/useChatStream` / `hooks/useSessionList`), all
  behavior-preserving with targeted regression.

### 5.13 Model Routing & LLM Output Reliability (M7 / M7-8)

- **Request-level model threading (M7)** — `model` flows from controller DTOs →
  `StreamRequest.attributes` → `ChatStreamService.preflight` →
  `prepareStream(..., model)` → `ChatService.runStream` wrapped by
  `ModelRoutingContext.runWith(model, ...)`; itinerary `generate`/`resume` follow the
  same pattern. Invalid models fail fast (`40005`), never silently fall back.
- **Thread-local hygiene (M7)** — `ModelRoutingContext` is `runWith`-wrapped with
  `finally clear` (including the routed-model slot); the reactive graph path carries
  the model via `RunnableConfig.metadata` because Reactor threads do not inherit
  ThreadLocals.
- **Main-agent routing normalization (M7-8)** — `RoutingChatClient` (dynamic proxy
  over `ChatClient` → request spec → call/stream response specs) extracts the last
  valid JSON array from the assistant reply at the `chatResponse()` exit. Combined
  with a strict array-only supervisor prompt, this keeps the 4-sub-agent pipeline
  intact even when the main model prefixes its routing array with prose.
- **Query-understanding hallucination guards (M7-8)** — `type` is kept only when the
  raw query contains a matching type keyword (config + synonym table); `keywords`
  are filtered to substrings of the raw query and de-duplicated; validation happens
  before the LRU cache write. Zero-result type-filtered retrievals fall back to a
  city-only retry once (e.g. a Hangzhou-food query with no FOOD POIs returns
  Hangzhou candidates).
- **Runtime reliability (M7-8)** — `TraceAspect` now covers `ChatService.runStream`
  (per-request message-snapshot ThreadLocal is cleared on the SSE path, preventing
  stale-history leakage across requests); Redis commands interrupted by cancellation
  are re-classified as turn cancellation (`INTERRUPTED` + WARN) instead of generic
  failures; SSE disconnects no longer produce `No converter for R ... text/event-stream`
  noise (adapter skips `complete()` on broken responses, `GlobalExceptionHandler`
  stays silent for SSE content type); rolling-summary validation compares against the
  full generation input (old summary + new messages).
- **Forward-looking plan (M7-9, pending)** — structured outputs (JSON mode /
  BeanOutputConverter) for query understanding, rule-based or dual-model summary
  validation, chat-intent vs. supervisor routing consistency, cache TTL/metrics, and
  framework-level routing-array tolerance; activated only when defined trigger
  signals appear (see `docs/business-records/M7-9-*`).

### 5.14 Retrieval Reliability & Conflict Validation (M8)

- **Structured fact pipeline (M8-1)** — `SearchResult` now carries
  city/type/address/openHours/ticketPrice/freeEntry/rating/recommendedDuration/dataSource;
  `AttractionEnricher` batch-backfills them from MySQL at the `AbstractRagStrategy`
  exit (single exit, score-neutral), and the chat/itinerary injection format is a
  structured fact card with a source note (null = knowledge base has no data).
- **Source labeling & grounding checks (M8-2)** — every candidate carries
  `dataSource`; `AttractionGroundingChecker` verifies generated attraction names
  against the candidate set (bidirectional contains + suffix normalization) and
  records `grounding_rate`/unmatched into `t_agent_trace` (observation mode);
  retrieval degradations are observable via `rag.routing.degraded` metrics and a
  `DEGRADED` trace status; the query-understanding synonym table is config-driven.
- **Deterministic conflict validation (M8-3)** — `ItineraryConflictValidator`
  (time overlap / open-hours / duration capacity / budget consistency rules) is
  mounted as a `conflict_check` graph node with a `conflict_retry` loop
  (budget_retry-style counter + feedback message); budget JSON parsing is now
  `JsonUtils`-based (`BudgetJsonParser`).
- **MCP web-search fallback (M8-4, default off)** — `WebSearchPort` +
  `McpWebSearchAdapter` (spring-ai-starter-mcp-client, rate-limited/quota'd/
  circuit-broken/time-boxed) fills only locally-missing openHours/ticketPrice,
  labels them `web_enrich`, and the injection adds a low-confidence notice.
- **Write-back loop (M8-5, default off)** — `WebEnrichWritebackService` fills only
  NULL columns with a 7-day MySQL debounce (`enrich_source`/`enrich_updated_at`),
  then triggers incremental ETL so the next request hits local data;
  `SourceConfidence` gained the lowest `WEB` level.
- **REFINE retention discipline (M8-6)** — supervisor/route prompts require
  explicit keep/adjust/add/delete labels; `checkRetention` observes silent-loss
  rates into the trace.

### 5.15 Engineering / Reliability Hardening (M9 / M10)

- **Exact-name ranking boost (M9-1)** — `ExactMatchBoostRule` promotes a candidate
  whose normalized name exactly or closely matches the raw query before enrichment
  (deterministic, zero-LLM, `travel.rag.quality.exact-match-boost`).
- **Full index rebuild (M9-1b)** — MySQL 799 rows were re-embedded into Milvus/ES
  with the 2026-09-01 manually enriched fields; RAG hard gate was re-baselined to
  **Recall@5=0.8685 / MRR@5=0.8741** (45 queries, `--no-llm`).
- **Web-search provider registry & async fill (M9-2)** — `WebSearchProviderRegistry`
  provides priority/failover across Tavily/MCP/Noop; `fill-mode=async` keeps the
  main retrieval path synchronous-free and writes results through
  `WebEnrichWritebackService` for the next request; `OpenHoursParser` was moved to
  `travel-common` for cross-module reuse.
- **Graph-flow oscillation observability (M9-3)** — stream executor counts repeated
  node executions and emits `graphFlowWarnings` into the trace callPath (dispatch
  dedup/cache remain framework-level open items, see M9-3 record).
- **Chat-path conflict observation (M9-4)** — `ItineraryConflictPort` exposes the
  deterministic validator to chat supervision without a module dependency inversion;
  violations are written into `t_agent_trace` callPath (`chatConflictViolations`).
- **Engineering cleanup (M10-1/M10-2)** — mcp-command config alignment and ETL
  batch-size property; chat 40303 UX moved into `useChatStream`; ItineraryService
  was split from 840 lines into a 218-line facade plus orchestrator/coordinator/
  graph-executor/slice-writer/dto-assembler; split-package cleaned;
  `SessionStatus` enum; per-model circuit breaker (`ModelCircuitGuard`, 40304);
  Hikari `max-lifetime`; Redis-interrupt WARN downgraded to DEBUG.

### 5.16 Business Extensions (M11)

- **M11-4 Profile consumption level** — `t_travel_profile.consume_level`
  (ECONOMICAL/STANDARD/COMFORT) is extracted by preference saving, exposed to
  agents/profile context, and used by the budget prompt to scale meal prices
  (×0.6 / ×1.0 / ×1.8).
- **M11-2 Itinerary map** — `AttractionVisit` now carries latitude/longitude filled
  by `ItineraryCoordinateDecorator`; the itinerary detail page lazy-loads a Leaflet
  map grouped by day with polylines and a missing-coordinate fallback.
- **M11-3 Reliability dashboard** — `GET /api/v1/admin/reliability/stats`
  aggregates `t_agent_trace` (grounding/retention/degraded/model distribution/top
  nodes) behind `travel.admin.user-ids`; `/admin/reliability` renders metrics,
  bar chart and tables.
- **M11-1 Itinerary versioning** — `t_itinerary.version`/`version_diff` plus
  `t_itinerary_version` snapshots; every finalized content change creates a new
  version with a deterministic kept/adjusted/added/removed diff; version list and
  detail endpoints + frontend history drawer.

### 5.17 Map real road network & AMap quota control (M12)

- **M12-1 AMap route/geocode adapters** — reuses the crawler Web-service Key
  (`AMAP_WEB_API_KEY`) on the backend only: v3 walking/driving direction parsing,
  geocoding, Port-Adapter + Noop rollback; no key leaves the 8081 service.
- **M12-2 Route orchestration, Redis cache & quota guard** — new
  `GET /api/v1/itineraries/{id}/map-routes`; segment-level cache
  (`travel:map:route:v1:*`, 30d; geocode 90d; empty 1d), daily/monthly Redis budgets
  (route 4500/120000, geocode 3000/30000), ≤2 QPS pacing, circuit breaker and
  LOCAL_FALLBACK degradation; `RequestThrottle`/`QuotaGuard` moved to travel-core.
- **M12-3 Frontend map upgrade** — AMap tiles (no key) with OSM fallback, real
  road-network polylines vs dashed schematic curves, hotel anchor markers, route
  loading/error states; `lib/itinerary-map-utils.ts` pure functions + Vitest.

### 5.18 20260904 review implementation (M13–M15)

- **M13 External activation & REFINE version loop** — AMAP/TAVILY activated via
  environment keys; chat planning writes back into `t_itinerary`
  (create-on-chat / REFINE v2+ with deterministic diff), version history drawer and
  rollback-to-new-version are exposed in the itinerary detail modal; a
  React-agent BEFORE_AGENT spike (`DedupSubAgentHook`) is production-ready but
  **disabled by default** (`travel.chat.supervisor.dispatch-dedup.enabled=false`).
- **M14-1a Five-piece bean-ification** — ItineraryService no longer lazily new()s
  DtoAssembler/SliceWriter/ResumeCoordinator/GraphExecutor/GenerationOrchestrator;
  all six collaborators are `final` constructor-injected beans (19-arg Lombok
  constructor, test factories synchronized).
- **M14-1b Meal consume_level hard rule** — `ItineraryConflictValidator` adds a
  deterministic fifth rule: meal estimate must lie within
  `days×people×200×consumeFactor×(1±30%)`; mismatch is a WARNING appended to
  `budgetEstimate.notes`, never a retry.
- **M14-1c Dashboard & itinerary token alignment** — reliability stats now expose
  token totals/daily trend, model×duration (avg/P50/P95/max) and AMap route/geocode
  quota water levels; `ItineraryGraphExecutor` begins/ends the shared
  `TokenUsageInterceptor` with the trace requestId so itinerary graph tokens land
  in `t_agent_trace`.
- **M15-1 Open-Meteo weather (default off)** — zero-key weather Port + adapter
  with Redis geo/forecast caches, daily 3000-budget/≤1 QPS/circuit-breaker guard,
  weather context injected into itinerary generation/resume and daily weather
  badges on the map page; `travel.weather.enabled=false`.
- **M15-2 Map enhancement** — markers are colored by POI type
  (CULTURE/NATURE/FOOD/SHOPPING/FAMILY/LEISURE), route segments show walking/driving
  mode badges, and a play button sequentially highlights each day's routes
  (respects `prefers-reduced-motion`).

### 5.19 Live-Test Hardening & Preference Pipeline (M16–M28)

The final development arc was driven by **seventeen rounds of live user testing**
(each round: reproduce from three backend logs → root-cause → fix → redline
regression → business record → push), culminating in release v2.0.5.26:

- **Config single-source (M16/M18)** — all chat-domain configuration (model
  registry, memory, RAG, guard word lists, CORS, JWT) lives in
  `application-chat.yml`, auto-loaded at lowest precedence by
  `ChatDomainConfigEnvironmentPostProcessor`; word lists (intents / heuristics /
  chunker / fact / preference) moved to the same single-source yml.
- **Session anchoring (E1, M23/M26/M28)** — `t_chat_session.anchored_itinerary_ids`
  persists the anchor set; every chat message carries a per-turn anchor snapshot
  (`anchoredItineraryIds`) threaded through `prepareStream`. The first itinerary
  generated in a session is auto-anchored; the anchor panel is single-select
  (picking another itinerary replaces the anchor) and every switch persists a
  `SYSTEM`-role message ("switched from itinerary X to Y") rendered as a centered
  translucent row in the chat history.
- **Per-turn preference tags (E4, M23b–M28)** — `PreferenceTagsDTO` rides on each
  message body (`preferences`) and is rendered deterministically into a
  preference-constraint prompt section with destination-conflict hint lines. The SSE
  `done` payload carries `preferenceSync` (destination/days/budget/party/interests/
  startDate read back from the itinerary constraint columns) which the frontend
  merges into the per-session tag store (localStorage). The pipeline is fully
  end-to-end: both transports put `preferences` into `StreamRequest.attributes`,
  and `ChatService` implements the 7-parameter `prepareStream` so the tags flow
  through `ChatStreamPrepared` (the interface default used to silently drop them —
  proven by a live browser fetch capture showing the frontend had been sending them
  all along, M28-17).
- **Chat itinerary writeback & version snapshots (M13/M26–M28)** — chat PLANNING
  creates a first-class itinerary and REFINE rewrites it with a deterministic diff;
  constraint columns (budget/days/start_date/party/interests) are updated from
  explicitly extracted user input — `extractExplicitInput` keeps only the preference
  and current-question sections, so profile text such as "usual companions: family"
  can never pollute deterministic parsing. `t_itinerary_version` snapshots
  days/budget/start_date at write time so version switching restores exactly the
  constraints of that era.
- **Session-fact consensus (F85/M28-10)** — constraint/feedback slices are merged
  per topic with latest-wins semantics; one statement can update several topics
  ("change companions to couple and days to 5"); values are canonicalized with a
  party mapping aligned to the frontend option list.
- **Streaming resilience (M28-10/15)** — the frontend chat stream state lives in a
  module-level store: client-side route switches (chat ⇄ itinerary) only
  unsubscribe, thinking continues in the background, and away results are consumed
  on return (history reload + preference merge + sending-lock restore). Only a
  browser refresh/close or an explicit stop interrupts a turn. A React StrictMode
  double-invocation bug in the anchor toggle (side effects inside a setState
  updater) was also fixed by reading current state from a synced ref.
- **Context & token governance (M28-8/9)** — rolling summaries, a two-turn recent
  window, and an input-token hard cap with a four-tier conservative fallback;
  `[ChatPreference]` reachability log pairs (tags carried / sync payload) give a
  three-segment localization for any preference issue.
- **Ops conveniences (M27)** — RFC 5545 ICS calendar export (UTF-8 octet folding,
  per-day VEVENTs), double-click title rename everywhere (session list, card modal,
  detail page; same value = zero network request), E3 detour three-gate
  observability quantification (`check_e3_observation.py`).

Implementation details and per-step verification live under
`docs/business-records/` (M16–M28 records; the M26–M28 live-test rounds are
collected in the M26 live-test business record under `docs/business-records/`, file
name starting with `M26-20260907`).

---

## 6. Implementation Principles

1. **Grounding over generation** — the LLM is a *reasoner*, not a database. Facts come from the RAG knowledge base.
2. **Orchestration as a graph** — complex flows are explicit state machines, not free-form prompts.
3. **Multi-agent specialization** — a supervisor delegates to focused agents instead of one giant prompt.
4. **Memory is a first-class citizen** — three memory layers are read/written explicitly at every turn.
5. **Safety by default** — guards sit *in front of* the LLM and storage layers.
6. **Everything is traceable** — every AI decision has an audit trail.
7. **Separate concerns by module** — crawl (data acquisition), knowledge (retrieval), planning (reasoning), common (shared) are independently deployable services.
8. **Streaming is the default UX** — chat responses stream as thinking/token events, and cancellation is a first-class, transport-agnostic concern (M6).
9. **User intent is deterministic where it can be** — constraint parsing, conflict
   hints, and preference sync are rule-based; the LLM reasons, rules record (M26–M28).

---

## 7. End-to-End Data Flow

```
1. User logs in → JWT issued (AuthController)
2. User chats: "Plan a 5-day trip to Chengdu on a 3000 CNY budget"
   └─▶ ChatController (or WebFlux controller) → SupervisorAgent
        └─▶ 9-step MessagePipeline: Guard→Persist(idempotency key)→Preference→Knowledge→Intent→Memory→Budget→Route→Reply persist
        └─▶ Preference tags (per-turn) + anchored-itinerary sections rendered into the prompt
        └─▶ PreferenceAgent extracts constraints (5 days, Chengdu, ¥3000)
        └─▶ QueryUnderstanding (in knowledge module) refines the query
        └─▶ RagDispatcher → HybridRag → ES + Milvus retrieval (+ structured fact backfill)
        └─▶ StateGraph: Plan node builds day-by-day itinerary → conflict check loop
        └─▶ Budget node estimates costs; replan loop if over budget
        └─▶ Output node produces structured JSON + markdown
        └─▶ Itinerary writeback: create-on-chat / REFINE + constraint columns + version snapshot
        └─▶ Trace recorder writes the full audit trail
        └─▶ SSE transport streams thinking → token → done (MVC SseEmitter :8081 or WebFlux :8083)
             done carries preferenceSync (itinerary constraint read-back) for the frontend tags
        └─▶ stop → interrupt endpoint → INTERRUPTED; retry reuses the same idempotency key
   (recovery: FAILED/zombie-GENERATING itineraries can resume from breakpoints; chat streams can
    resume with Last-Event-ID + same idempotency key; sessions can be closed with summary finalize;
    client-side route switches during streaming do NOT interrupt the turn)
3. Frontend renders: markdown itinerary card + mind map + budget chart + road-network map
4. User edits / follows up → chat memory + long-term memory + session tags updated
```

---

## 8. Database Design

Core tables (initialized by `scripts/init_mysql.sql` + incremental migrations in `scripts/sql/`):

| Table                                  | Purpose                                             |
| -------------------------------------- | --------------------------------------------------- |
| `t_user`                               | Accounts (BCrypt password, email)                    |
| `t_travel_profile`                     | Long-term profile (preferences/budget/style/history, version optimistic lock, consume_level) |
| `t_itinerary`                          | Itinerary entity (GENERATING/GENERATED/FAILED; version/version_diff; constraint columns budget/days/start_date/party/interests kept authoritative) |
| `t_itinerary_task_snapshot`            | Itinerary node snapshots (M4-8, for resume)          |
| `t_itinerary_version`                  | Itinerary historical snapshots (version + diff + content + days/budget/start_date constraint snapshot, M11-1/M28-7) |
| `t_attraction`                         | POI knowledge base (799 rows; source of truth for retrieval + enrichment write-back) |
| `t_chat_session` / `t_chat_message`    | Sessions (ACTIVE/ARCHIVED + summary_final + anchored_itinerary_ids) and message history (user/assistant/SYSTEM roles) |
| `t_chat_message_idem`                  | Message idempotency table (PENDING/COMPLETED/FAILED, M4-3) |
| `t_agent_trace`                        | Agent trace (RUNNING/SUCCESS/FAILED/TIMEOUT; grounding/retention/degraded observation columns) |
| `t_system_config`                      | System config (default RAG strategy, rate limits, etc.) |

`t_travel_profile` also carries `consume_level` (M11-4) and structured behavior
facts (M17); `t_behavior_profile` stores aggregated user behavior (M17).

The MySQL data is the **source of truth**; Elasticsearch and Milvus are derived indexes rebuilt by the ETL pipeline.

---

## 9. Deployment & Getting Started

### 9.1 Prerequisites

- **JDK 21** (backend)
- **Node.js 18+** (frontend)
- **Docker + Docker Compose** (middleware)
- **LLM API keys**: `DASHSCOPE_API_KEY` (required), `DEEPSEEK_API_KEY` / `GLM_API_KEY`
  (optional; registry marks them disabled when absent)
- **Optional data keys**: `AMAP_WEB_API_KEY` (road-network routes/geocoding),
  `TAVILY_API_KEY` (web-search enrichment) — both quota-guarded and optional
- **Required security env vars (M21-1/2/3)**: `JWT_SECRET` (no default since M21-1 —
  startup fails when missing) and `TRAVEL_INTERNAL_TOKEN` (shared secret across
  planning/webflux/knowledge/crawl; internal endpoints fail-closed when unset).
  Local development may set them in `application-local.yml` (gitignored).

### 9.2 Step 1 — Start Middleware

```bash
cp .env.example .env          # fill in your DASHSCOPE_API_KEY etc.
docker compose up -d          # starts MySQL, Redis, ES, Kibana, etcd, Milvus, MinIO ×2
docker compose ps             # verify all services healthy
```

> ⚠️ **ES requires `vm.max_map_count >= 262144`** on Linux hosts. Run `sudo sysctl -w vm.max_map_count=262144` first.

### 9.3 Step 2 — Initialize Data Infrastructure

```bash
cd scripts
bash init_all.sh              # MySQL schema + ES index + IK analyzer + Milvus collection + MinIO buckets
python init_milvus.py         # (idempotent) creates the vector collection
bash init_elasticsearch.sh    # creates the attraction index (with IK analyzer)
bash init_minio.sh            # creates required buckets
```

### 9.4 Step 3 — (Optional) Seed the Knowledge Base

```bash
python crawl_attractions.py   # fetch attraction data
# then trigger the ETL pipeline via EtlController:
#   POST /api/v1/etl/import?filePath=<abs>/scripts/data/attractions_raw.json&mode=insert
#   POST /api/v1/etl/all
# Canonical baseline: MySQL/ES/Milvus = 40/40/40, with seed ids
# 1=Forbidden City, 6=The Bund, 9=Terracotta Army Museum, 10=Big Wild Goose Pagoda
# Check/rebuild: python scripts/regression/check_baseline.py --names
#                python scripts/regression/reset_baseline.py --force (test env only, rebuilds from scratch)
```

### 9.4b Three-Layer Configuration & IDEA Launch (since M16-3)

Configuration precedence (high → low): `application-local.yml` (local profile,
gitignored; environment differences only — VM datasource / Redis / ES / MinIO /
API keys / intentional overrides) → per-process `application.yml` (ports and
process-specific sections) → `application-chat.yml` (the chat-domain single
source: model registry, memory, RAG, LLM, chat, guard word lists, tracing,
CORS, JWT; shared by both chat processes and auto-loaded at the lowest
precedence through `ChatDomainConfigEnvironmentPostProcessor` registered in
`travel-chat-domain` via `META-INF/spring.factories`. `spring.config.import` is
intentionally NOT used because IDEA cannot resolve dependent-module classpath
resources and fails to start).

**Launching from the IDEA Run button requires the local profile to be active**
(otherwise the datasource falls back to localhost and startup fails): Run
Configuration → `Active profiles: local` (or VM option
`-Dspring.profiles.active=local`). Command-line equivalent:
`java -jar app.jar --spring.profiles.active=local`.

**Never** duplicate chat-domain sections inside `application-local.yml` — it
silently overrides the single source and causes two-source drift (historical
leftovers were cleaned up in the M18 batch; regression scripts guard this with a
conditional redline).

### 9.5 Step 4 — Run Backend Services

```bash
# terminal 1 — knowledge service (:8082)
mvn -pl travel-knowledge spring-boot:run

# terminal 2 — planning service (:8081)
mvn -pl travel-planning spring-boot:run

# terminal 3 — reactive chat stream transport (:8083, optional; requires travel-chat-domain)
mvn -pl travel-stream-webflux spring-boot:run

# terminal 4 — crawl service (:8087, optional)
mvn -pl travel-crawl spring-boot:run
```

When running from IDEA, add the `local` profile to every Run Configuration (see
9.4b). After code changes that touch `travel-chat-domain` (the shared domain
module), **Rebuild the module in IDEA** (or `mvn install` it) before restarting
the webflux transport — it consumes the domain classes from its classpath.

API contract doc: `docs/test/backend-api-postman-testing-2026-08-23.md` (Knife4j removed)

### 9.6 Step 5 — Run Frontend

```bash
cd travel-frontend/next-app
npm install
npm run dev                  # http://localhost:3000
npm run dev:alt              # http://localhost:3100 (port used during development)
# production: npm run build && npm start

# optional: gray-switch chat SSE to the reactive transport (:8083)
# NEXT_PUBLIC_STREAM_BASE=http://localhost:8083 npm run dev:alt
```

### 9.7 Hybrid Deployment (as used in development)

- **Linux VM (Ubuntu 22)** runs all Docker middleware (MySQL, Redis, ES, Milvus, MinIO).
- **Windows host** runs IntelliJ IDEA for backend development and the Next.js dev server.
- Backend services on the Windows host connect to the VM's middleware via the VM's LAN IP.

---

## 10. Usage Guide & Tips

### 10.1 Typical User Journey

1. **Register / login** → create a profile with preferences.
2. **Open a new chat session** → optionally set per-turn preference tags
   (companion / interests / budget / dates) and pre-select an anchor itinerary
   from history before the first message.
3. **Chat with the AI** — describe your trip (destination, days, budget, companions).
4. **Review the generated itinerary** — markdown card + mind map + budget chart +
   road-network map; the first plan is auto-anchored as the session baseline.
5. **Iterate** — ask follow-ups ("make it 5 days", "companions to couple"); each
   REFINE writes a new version with a diff, and constraint columns/tags follow.
6. **Manage plans** in `/itinerary` (versions, rename, ICS export); browse the
   knowledge base in `/attractions`.

### 10.2 Prompting Tips (for best results)

- Be explicit about **budget, days, companions, and pace** ("relaxed", "packed").
- Mention **dietary or mobility constraints** — `PreferenceAgent` persists them.
- Ask for **comparisons** ("show 2 options") to trigger a more thorough RAG retrieval.
- If the answer seems incomplete, ask "based on the knowledge base only" to force grounded retrieval.
- Direct-answer rounds (profile questions, general chat) do not modify itineraries
  by design — ask for a planning adjustment when you want the itinerary changed.

### 10.3 Operations Tips

- **Re-index after data updates**: call the ETL endpoint or restart the scheduled job.
- **Monitor traces**: check `t_agent_trace` to debug unexpected agent behavior.
- **Rate limits**: tune `RateLimiter` config per environment.
- **Circuit breaker**: temporary provider outages trigger fallback; verify the fallback content is clearly labeled.
- **Preference debugging**: the backend logs a `[ChatPreference]` pair per round
  (tags carried by the request + sync payload written back) — a mismatch between
  them localizes any preference issue to one of three segments in one glance.

---

## 11. Project Milestones & Documentation

Development followed milestone-driven records in `docs/business-records/` (390 files):

| Milestone | Scope                                                                                                                                                                      |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **M0**    | Project scaffolding, Maven POM, environment setup                                                                                                                          |
| **M1-1**  | `travel-common` full implementation                                                                                                                                        |
| **M1-2**  | `travel-planning` Agent framework                                                                                                                                          |
| **M1-3**  | StateGraph workflow                                                                                                                                                        |
| **M1-4**  | RAG retrieval                                                                                                                                                              |
| **M1-5**  | ETL pipeline                                                                                                                                                               |
| **M2-1**  | Controller layer                                                                                                                                                           |
| **M2-2**  | Service layer enhancements                                                                                                                                                 |
| **M2-3**  | Next.js frontend implementation                                                                                                                                             |
| **M2-4**  | F-series iterations: three-layer memory (F47/F49), query understanding & strategy routing (F40), frontend architecture & Agent trace & security (F88), and 100+ more fixes |
| **M2-5**  | Itinerary budget breakdown output optimization                                                                                                                              |
| **M3**    | Ten-phase optimization (M3-1~22): MessagePipeline 9-step chain, common dependency sinking, prompt externalization, frontend hardening, profile optimistic-lock retry       |
| **M4**    | Three-direction optimization (M4-1~11): session compression (Lua CAS/close finalize), RAG reliability (evaluation/Judge/Rerank/parent-context), state recovery (message idempotency/itinerary state machine+resume); integration round fully green (199 unit tests, F104 28/28, RAG evaluation baseline) |
| **M5**    | Frontend experience optimization (M5-1~2): chat page interaction polish, form/dropdown/theme/URL-encryption hardening                                                       |
| **M6**    | Streaming & lifecycle hardening (M6-1~58): SSE streaming pipeline, dual MVC/WebFlux transport, real token usage, turn cancellation (stop/retry/refresh recovery), itinerary resume fixes (edge completion/resume context/SSE decoupling/auto-poll), external-review-driven refactor (Supervisor + chat page split) |
| **M7**    | Multi-model gateway (M7-0~5) + runtime & LLM output reliability (M7-6~8): explicit light-role wiring, actual-model trace, SSE-disconnect / Redis-interrupt / ThreadLocal fixes, query-understanding hallucination validation, zero-result RAG fallback, main-agent routing normalization; forward-looking plan M7-9 (pending) |
| **M8**    | Retrieval reliability & conflict validation (M8-0~6): structured fact pipeline, source labeling + grounding/retention checks, deterministic conflict rules + in-graph retry, MCP web-search fallback (off) + write-back loop (off), REFINE retention discipline; RAG 45-golden gate PASS |
| **M9**    | Retrieval supply close-out & runtime hardening: exact-name boost, full ES/Milvus rebuild (799/799), baseline refreshed to 0.8685/0.8741, web-search provider registry + async fill, graph-flow oscillation warning, chat conflict observation |
| **M10**   | Engineering close-out: ItineraryService split (840→218), chat 40303 UX into hooks, split-package cleanup, SessionStatus enum, per-model circuit breaker (40304), config/Hikari/Redis-log cleanup |
| **M11**   | Business extensions: itinerary versioning & diff drawer, Leaflet day-map with coordinate backfill, reliability dashboard (admin whitelist), profile consume-level budget personalization |
| **M12**   | Map real road network & quota control: travel-core guard components; AMap v3 route/geocode adapters; map-routes endpoint with Redis cache + daily/monthly budgets; AMap-tile frontend with hotel anchors and schematic fallback |
| **M13**   | External-service activation & REFINE version loop: AMap/Tavily env keys, chat-path itinerary writeback (create-on-chat / REFINE v2+ deterministic diff), version drawer, dispatch-dedup spike (off by default) |
| **M14**   | Bean hardening & deterministic rules: six-collaborator bean-ification, meal consume-level hard rule, dashboard/itinerary token alignment into `t_agent_trace`             |
| **M15**   | Open-Meteo weather port (default off) with Redis caches & quota guard; map POI-type coloring, mode badges, day-by-day playback                                              |
| **M16–M19** | Platform & config hardening: `application-chat.yml` single source via `EnvironmentPostProcessor`, chat word lists single-source yml, behavior-profile & structured-profile persistence, local-config drift cleanup |
| **M20–M22** | Reliability & security: chat-path behavior-profile recompute trigger, mandatory `JWT_SECRET`, fail-closed `X-Internal-Token` inter-service auth, grounding observation columns |
| **M23–M25** | Session anchoring (E1/E2) & per-turn preference tags (E3/E4): `t_chat_session.anchored_itinerary_ids`, anchor panel + suggestion card, `PreferenceTagsDTO` per-message truth, preference-conflict card, detour three-gate observability (default off) |
| **M26**   | Dual-transport live-fix round (v2.0.5.0~): webflux internal HTTP bridge (X-Internal-Token), CORS preflight/PATCH, constraint-column writeback (budget/days), date anti-hallucination, preference sync channel |
| **M27**   | Demo sprint (v2.0.5.9): RFC 5545 ICS export, PATCH title rename with same-value zero-request, E3 observability quantification, E2E smoke suites |
| **M28**   | Live-test hardening (v2.0.5.10~.26, seventeen feedback rounds): session-fact multi-topic consensus, purified explicit-input writeback, preferences end-to-end through the 7-param `prepareStream`, itinerary preference metadata (`party`/`interests`), single-anchor UX + persisted system messages, SSE route-switch resilience, TS71007 cleanup |

Each module also has a **business development record** markdown documenting design decisions, implementation details, and self-review results — a key academic artifact of the thesis.

---

## 12. Scripts Reference

| Script                  | Purpose                                                                      |
| ----------------------- | ---------------------------------------------------------------------------- |
| `init_all.sh`           | One-shot init: MySQL schema, ES index + IK, Milvus collection, MinIO buckets |
| `init_mysql.sql`        | DDL for all business tables (baseline includes all constraint/snapshot columns) |
| `init_elasticsearch.sh` | Creates the attraction index with the IK Chinese analyzer                    |
| `install_es_ik.sh`      | Installs the IK analyzer plugin into ES                                      |
| `init_milvus.py`        | Creates the vector collection in Milvus                                      |
| `init_minio.sh`         | Creates required MinIO buckets                                               |
| `init_redis.sh`         | Redis readiness check / basic config                                         |
| `crawl_attractions.py`  | Seeds attraction data from public sources                                    |
| `regression/`           | Regression test harness for the agent pipeline                               |
| `data/`                 | Local data artifacts used by scripts                                         |
| `sql/m4_*.sql`          | M4 migrations (idempotency/snapshot/summary_final, with rollback)           |
| `sql/m11_*.sql` / `sql/m13_*.sql` / `sql/m17_*.sql` | Incremental migrations (versioning/consume_level, session link, behavior/structured profile) |
| `sql/m23_session_anchor.sql` / `sql/m28_7_itinerary_version_constraints.sql` | Session-anchor column and version constraint snapshot columns (idempotent increments) |
| `sql/m8_*.sql`          | M8 migrations (grounding observation, web-enrich columns, AMap null cleanup) |
| `regression/run_full_regression.ps1` | Full-regression orchestrator (P1/P2/P3/F85/M4/F104/RAG eval; supports `-RepairBaseline`) |
| `regression/run_rag_eval.ps1` | RAG offline evaluation (45 golden queries, hard/soft gates, `--write-baseline`) |
| `regression/run_m23_regression.ps1` / `run_m26_regression.ps1` / `run_m27_regression.ps1` | Targeted redline suites accumulating M23~M28 fixes (offline, deterministic) |
| `regression/check_baseline.py` / `reset_baseline.py` | Three-end baseline check and canonical baseline rebuild |
| `regression/check_e3_observation.py` | E3 detour three-gate observability quantification (D-V8-4) |

---

## 13. Troubleshooting & FAQ

**Q: Elasticsearch fails to start with a memory-map error.**  
Set `vm.max_map_count=262144` on the host: `sudo sysctl -w vm.max_map_count=262144`.

**Q: Milvus won't start on older hardware.**  
Milvus requires AVX instruction set support. Use the docker-compose `milvusdb/milvus` image and verify CPU flags; fall back to `minimal` profile if needed.

**Q: The AI answers are not grounded in real attraction data.**

1. Confirm the ETL pipeline ran and documents are indexed (`GET localhost:9200/attraction_index/_count`).
2. Confirm Milvus collection is non-empty (`python init_milvus.py` prints counts).
3. Check `RagDispatcher` strategy selection in traces — the query may have been classified as "general chat" and skipped retrieval.

**Q: Rate limiting kicks in during testing.**  
Raise the limits in the `RateLimiter` configuration, or disable per-profile in local dev.

**Q: Where do I see agent internals?**  
Query the `t_agent_trace` table (latest first) for the full execution audit trail of any request.

**Q: How do I run the full regression?**  
Run `mvn -o package -DskipTests` first, then
`powershell -File scripts\regression\run_full_regression.ps1`
(middleware must be reachable; add `-RepairBaseline` when the baseline has drifted). See
`docs/test/full-regression-script-guide-2026-08-23.md` for details.

**Q: The three-end baseline became 40/40/0 or inconsistent?**  
Diagnose with `python scripts/regression/check_baseline.py --names`; when ES/Milvus were wiped
or ids drifted, rebuild the canonical baseline in a test environment with
`reset_baseline.py --force` (truncates t_attraction + seeds 10 + raw import + etl/all).

**Q: P3/F85 randomly hit ReadTimeout in full regression?**  
Caused by slow DashScope windows (a single chain exceeding the backend 300s hard timeout);
the orchestrator records it as WARN, and re-running the suite when the LLM is idle turns green.
Deterministic gates (unit tests / P1 / P2 / F104 / RAG hard gates / baseline) are unaffected.

**Q: How do I switch to a different LLM?**  
`spring-ai` supports multiple providers. Swap the DashScope starter for the corresponding Spring AI starter (e.g. OpenAI) and change the model config in `application.yml` — the agent framework and RAG pipeline are provider-agnostic.

**Q: How do I inspect itinerary historical versions / diffs?**  
Open an itinerary detail page → "History Versions". Backend endpoints are
`GET /api/v1/itineraries/{id}/versions` and
`GET /api/v1/itineraries/{id}/versions/{version}`. Data comes from
`t_itinerary_version`; current-row version metadata is on `t_itinerary`.
Switching a version also restores the days/budget/start_date constraints
snapshotted at that era (M28-7).

**Q: Why does the itinerary map sometimes not render markers?**  
Markers require `t_attraction.lat/lng` and a route-plan name that can be matched
by `AttractionGroundingChecker`; otherwise the component shows the
"no available coordinates" fallback.

**Q: Reliability dashboard returns 403?**  
Set `travel.admin.user-ids` (or env `ADMIN_USER_IDS`) to the comma-separated
whitelist of your own user ids before calling `/api/v1/admin/reliability/stats`.

**Q: Preference tags I set in a new session did not reach the AI.**  
Check the backend log for the `[ChatPreference]` tags-carried line: if it prints
"(not carried)" while the browser Network tab shows `preferences` in the request
body, the chat-domain classes are stale — rebuild/reinstall `travel-chat-domain`
and restart both chat processes (fixed end-to-end in v2.0.5.26; the 7-parameter
`prepareStream` must be present in the running classes).

**Q: A chat turn was interrupted when I navigated to the itinerary page.**  
Only a browser refresh/close or an explicit stop interrupts a turn (since
v2.0.5.15). If you still see interruptions after route switches, hard-refresh the
frontend (Ctrl+Shift+R) — a stale HMR bundle may be running an older module store.

---

## License

All rights reserved. This project is a personal thesis work; please contact the author before reuse.
