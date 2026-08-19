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
| **Personalization**        | User profile + multi-round dialogue → preference extraction → budget-aware planning           |
| **Factuality / Grounding** | RAG over a curated attraction knowledge base (Elasticsearch + Milvus vector search)           |
| **Iterative planning**     | Multi-agent collaboration (supervisor + specialized sub-agents)                               |
| **Controllable workflow**  | Spring AI Alibaba **StateGraph** — every step is a graph node with explicit state transitions |
| **Explainability**         | Agent trace recording (each agent step, tool call, and reasoning is persisted)                |
| **Safety**                 | Prompt injection guard, rate limiting, and circuit breaking at the service layer              |

### 1.3 Development Context

This is a **graduate thesis project** developed iteratively under a milestone-driven workflow (M0 → M2-5), with **108+ recorded bug-fixes and feature iterations (F1–F108)**. Every milestone and fix is documented in `docs/business-records/` (157 markdown files), following a strict "design → implement → self-review" discipline.

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
| **MyBatis-Plus**                        | 3.5.7                 | ORM with rich CRUD, pagination, and codegen support                                                                 |
| **Redisson**                            | 3.28.0                | Distributed locks, rate limiting, Redis-backed cache                                                                |
| **Milvus SDK**                          | 2.3.4                 | Vector database client for semantic similarity search                                                               |
| **MinIO**                               | 8.5.7                 | S3-compatible object storage for crawled data & avatars                                                             |
| **Elasticsearch**                       | 7.17.18               | Full-text search over the attraction knowledge base                                                                 |
| **POI / PDFBox**                        | 5.2.5 / 3.0.1         | Document parsing in the ETL pipeline (Excel, PDF)                                                                   |
| **Hutool**                              | 5.8.28                | Utility library (HTTP, crypto, date, file)                                                                          |
| **Knife4j**                             | 4.5.0                 | OpenAPI 3 UI for interactive API documentation                                                                      |
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

- **Alibaba Cloud DashScope** (Qwen series models) via `spring-ai-alibaba-starter-dashscope`.
- Model & key are configured through environment variables (see `.env.example`).

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
│                    travel-planning  (:8080)                        │
│  ┌───────────────┐  ┌─────────────────────────────────────────┐    │
│  │ Controllers   │  │         Multi-Agent Framework           │    │
│  │ Auth/Chat/    │  │  TravelSupervisorAgent (orchestrator)   │    │
│  │ Itinerary/    │  │    ├─ AttractionAgent                    │    │
│  │ Avatar        │  │    ├─ RouteAgent                         │    │
│  └───────┬───────┘  │    ├─ BudgetAgent                        │    │
│          │          │    └─ PreferenceAgent                    │    │
│          │          │  StateGraph (TravelWorkflowBuilder)      │    │
│          │          │  理解→检索→规划→预算→输出 (nodes/edges)    │    │
│          │          └─────────────────────────────────────────┘    │
│          │  3-Layer Memory · PromptGuard · RateLimiter · Trace     │
└──────────┼──────────┬──────────────────────┬──────────────────────┘
           │          │                      │
┌──────────▼─────────┐│ ┌───────────────────▼──────────────────────┐
│   travel-knowledge ││ │          travel-crawl  (:8082)           │
│        (:8081)     ││ │  jsoup crawler · file queue · MinIO      │
│  ETL Pipeline      ││ │  schedule jobs · PipelinePublisher       │
│  (POI/PDF/HTML→     ││ └───────────────────┬────────────────────┘
│   chunks→embeddings)││                     │
│  RagDispatcher     ││                     │
│  ├ NaiveRag        ││                     ▼
│  ├ HybridRag       ││        Elasticsearch (full-text index)
│  ├ SelfRag         ││        Milvus (vector index) ← MinIO
│  └ CorrectiveRag   ││        MySQL (source data)
│  QueryUnderstanding│└── travel-common (shared DTOs/entities/utils)
└────────────────────┘
```

---

## 4. Module Breakdown

The backend is a **Maven multi-module** project (`pom.xml`, Java 21). Four modules + one frontend app:

```
travel-planner/
├── pom.xml                 # Parent POM (dependency & version management)
├── docker-compose.yml      # All middleware services
├── travel-common/          # Shared entities, DTOs, enums, utils, exceptions
├── travel-crawl/           # Web crawler for attraction data
├── travel-knowledge/       # ETL + RAG knowledge engine
├── travel-planning/        # Core agentic planning service (main app)
└── travel-frontend/
    └── next-app/           # Next.js 14 frontend
```

### 4.1 travel-common

Shared library, no business logic. Contains:

- **Entities & DTOs** — user, itinerary, attraction, chat message, memory models.
- **Enums** — itinerary status, RAG strategy types, memory types, error codes.
- **Utils** — JSON, date, crypto (SM4/AES), string, geo-distance helpers.
- **Common exceptions & response wrappers** — unified `Result<T>` envelope.

### 4.2 travel-crawl (`:8082`)

Attraction data ingestion pipeline:

- **Crawler service** (`CrawlService`) — jsoup-based web scraping of attraction POIs.
- **File queue & store** — downloaded assets (images, docs) persisted to MinIO.
- **PipelinePublisher** — publishes crawl results downstream (to knowledge module).
- **Scheduled jobs** — periodic re-crawl / incremental updates.

### 4.3 travel-knowledge (`:8081`)

The **knowledge engine** — makes the LLM grounded and factual:

- **ETL Pipeline** (`etl/`) — extract from raw sources (HTML/Excel/PDF via POI & PDFBox), clean, chunk, embed, and index into **Elasticsearch** (full-text) + **Milvus** (vectors).
- **RagDispatcher** — runtime dispatcher that selects a RAG strategy per query.
- **RAG Strategies** (`rag/strategy/`):
  - `NaiveRagStrategy` — top-k vector similarity retrieval.
  - `HybridRagStrategy` — fused ES keyword + Milvus vector scores.
  - `SelfRagStrategy` — self-reflection loop: generate → critique → regenerate if needed.
  - `CorrectiveRagStrategy` — query rewriting / fallback retrieval when the first pass is poor.
- **QueryUnderstanding** — classifies intent & entities, extracts constraints (budget, days, companions) before retrieval.
- **Controllers** — `AttractionController` (POI CRUD/search), `EtlController` (pipeline trigger), `RagController` (retrieval debug), `FileController` (MinIO upload/download), `MemoryController` (knowledge memory ops).

### 4.4 travel-planning (`:8080`)

The **core service** where agents collaborate:

- **Controllers** — `AuthController` (register/login/JWT), `ChatController` (multi-turn dialogue), `ItineraryController` (create/view/list itineraries), `AvatarController` (user avatar upload via MinIO).
- **Multi-Agent Framework** (`agent/`):
  - `TravelSupervisorAgent` — orchestrator that routes user intent to the right sub-agent.
  - `AttractionAgent` — attraction recommendation grounded in RAG.
  - `RouteAgent` — day-by-day route & sequencing.
  - `BudgetAgent` — cost estimation & breakdown.
  - `PreferenceAgent` — preference extraction & profile update.
- **Workflow** (`workflow/`) — `TravelWorkflowBuilder` assembles a **StateGraph**: `[Query Understanding] → [Retrieval] → [Planning] → [Budget] → [Output]` with explicit state transitions.
- **Memory System** (`memory/`) — three layers:
  - `shortterm` — session-scoped working memory (current trip context).
  - `longterm` — cross-session user preferences & facts.
  - `chat` — conversation history memory.
  - `knowledge` — knowledge-oriented memory (`KnowledgeRetrievalService`) bridging to the knowledge module.
  - `sessionstore` — Redis-backed session store.
- **Guard Layer** (`guard/`) — `PromptGuard` (prompt injection detection), rate limiting, and circuit breaker protecting LLM calls.
- **Trace** (`trace/`) — per-request agent trace: every node execution, tool call, and LLM exchange is recorded for explainability and debugging.

### 4.5 travel-frontend (`next-app`, port 3000)

Next.js 14 App Router application:

- **Routes**: `/login`, `/register`, `/chat` (AI dialogue), `/plan` (plan creation), `/itinerary` (view & manage), `/profile` (user settings), `/attractions` (browse POI knowledge base).
- **Key components**:
  - `markmap-view.tsx` — interactive itinerary mind map.
  - `theme-provider.tsx` / `theme-toggle.tsx` — dark/light theme.
  - `prefetch-provider.tsx` — route prefetch optimization.
  - `feature/` — feature-specific UI modules (chat, plan forms, itinerary cards).
  - `ui/` — reusable UI primitives.
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

### 5.3 RAG Pipeline (Four Strategies)

`RagDispatcher` selects a strategy based on query type and retrieval confidence:

| Strategy           | Mechanism                                                   | Use Case                                          |
| ------------------ | ----------------------------------------------------------- | ------------------------------------------------- |
| **Naive RAG**      | Embed query → Milvus top-k → prompt context                 | Simple factual attraction questions               |
| **Hybrid RAG**     | ES BM25 + Milvus vector scores fused (RRF)                  | Broad questions needing keyword + semantic match  |
| **Self-RAG**       | Generate → self-critique → regenerate if insufficient       | Questions where initial answers may be incomplete |
| **Corrective RAG** | Evaluate retrieval quality → rewrite query or switch source | Low-confidence retrievals                         |

The retrieval context is **pinned to the prompt with source attribution**, and the agent is instructed to answer *only* from the provided context or explicitly say it does not know — reducing hallucination.

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

### 5.6 Security & Guardrails

- **PromptGuard** — detects prompt-injection patterns and jailbreak attempts before they reach the LLM.
- **Rate Limiter** — Redis/Redisson-based token-bucket rate limiting per user/IP on chat & RAG endpoints.
- **Circuit Breaker** — protects the LLM provider (DashScope) from cascading failures; falls back to cached responses.
- **JWT Auth** — stateless token auth; passwords stored hashed.
- **Input validation** — zod (frontend) + Bean Validation (backend).

### 5.7 Agent Trace (Explainability)

Each planning request produces a **trace record**: node ID, agent invoked, tool calls, prompt/response snapshot, timestamps, and token usage. This powers:

- Debugging complex agent behavior.
- Showing the user *why* a recommendation was made.
- Regression testing of the pipeline (see `scripts/regression/`).

---

## 6. Implementation Principles

1. **Grounding over generation** — the LLM is a *reasoner*, not a database. Facts come from the RAG knowledge base.
2. **Orchestration as a graph** — complex flows are explicit state machines, not free-form prompts.
3. **Multi-agent specialization** — a supervisor delegates to focused agents instead of one giant prompt.
4. **Memory is a first-class citizen** — three memory layers are read/written explicitly at every turn.
5. **Safety by default** — guards sit *in front of* the LLM and storage layers.
6. **Everything is traceable** — every AI decision has an audit trail.
7. **Separate concerns by module** — crawl (data acquisition), knowledge (retrieval), planning (reasoning), common (shared) are independently deployable services.

---

## 7. End-to-End Data Flow

```
1. User logs in → JWT issued (AuthController)
2. User chats: "Plan a 5-day trip to Chengdu on a 3000 CNY budget"
   └─▶ ChatController → SupervisorAgent
        └─▶ PreferenceAgent extracts constraints (5 days, Chengdu, ¥3000)
        └─▶ QueryUnderstanding (in knowledge module) refines the query
        └─▶ RagDispatcher → HybridRag → ES + Milvus retrieval
        └─▶ StateGraph: Plan node builds day-by-day itinerary
        └─▶ Budget node estimates costs; replan loop if over budget
        └─▶ Output node produces structured JSON + markdown
        └─▶ Trace recorder writes the full audit trail
3. Frontend renders: markdown itinerary card + mind map + budget chart
4. User edits / follows up → chat memory + long-term memory updated
```

---

## 8. Database Design

Core tables (initialized by `scripts/init_mysql.sql`):

| Table                                  | Purpose                                             |
| -------------------------------------- | --------------------------------------------------- |
| `user`                                 | Account, hashed password, profile                   |
| `user_profile`                         | Long-term preferences, budget habits, dietary needs |
| `itinerary`                            | Generated plans (main entity)                       |
| `itinerary_detail`                     | Day-by-day plan items                               |
| `attraction`                           | POI knowledge base (source of truth for ES/Milvus)  |
| `chat_message`                         | Dialogue history                                    |
| `memory_shortterm` / `memory_longterm` | Memory layer persistence                            |
| `agent_trace`                          | Audit trail of agent executions                     |

The MySQL data is the **source of truth**; Elasticsearch and Milvus are derived indexes rebuilt by the ETL pipeline.

---

## 9. Deployment & Getting Started

### 9.1 Prerequisites

- **JDK 21** (backend)
- **Node.js 18+** (frontend)
- **Docker + Docker Compose** (middleware)
- **Alibaba Cloud DashScope API Key** (LLM)

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
#   POST /api/knowledge/etl/run
```

### 9.5 Step 4 — Run Backend Services

```bash
# terminal 1 — knowledge service (:8081)
mvn -pl travel-knowledge spring-boot:run

# terminal 2 — planning service (:8080)
mvn -pl travel-planning spring-boot:run

# terminal 3 — crawl service (:8082, optional)
mvn -pl travel-crawl spring-boot:run
```

API docs (Knife4j): `http://localhost:8080/doc.html`

### 9.6 Step 5 — Run Frontend

```bash
cd travel-frontend/next-app
npm install
npm run dev                  # http://localhost:3000
# production: npm run build && npm start
```

### 9.7 Hybrid Deployment (as used in development)

- **Linux VM (Ubuntu 22, user  <your_user_name>)** runs all Docker middleware.
- **Windows host** runs IntelliJ IDEA for backend development and the Next.js dev server.
- Backend services on the Windows host connect to the VM's middleware via the VM's LAN IP.

---

## 10. Usage Guide & Tips

### 10.1 Typical User Journey

1. **Register / login** → create a profile with preferences.
2. **Chat with the AI** — describe your trip (destination, days, budget, companions).
3. **Review the generated itinerary** — markdown card + mind map + budget chart.
4. **Iterate** — ask follow-ups ("make day 2 cheaper", "swap in a museum").
5. **Manage plans** in `/itinerary`; browse the knowledge base in `/attractions`.

### 10.2 Prompting Tips (for best results)

- Be explicit about **budget, days, companions, and pace** ("relaxed", "packed").
- Mention **dietary or mobility constraints** — `PreferenceAgent` persists them.
- Ask for **comparisons** ("show 2 options") to trigger a more thorough RAG retrieval.
- If the answer seems incomplete, ask "based on the knowledge base only" to force grounded retrieval.

### 10.3 Operations Tips

- **Re-index after data updates**: call the ETL endpoint or restart the scheduled job.
- **Monitor traces**: check `agent_trace` table to debug unexpected agent behavior.
- **Rate limits**: tune `RateLimiter` config per environment.
- **Circuit breaker**: temporary DashScope outages trigger fallback; verify the fallback content is clearly labeled.

---

## 11. Project Milestones & Documentation

Development followed milestone-driven records in `docs/business-records/`:

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
| **M2-3**  | Next.js frontend implementation                                                                                                                                            |
| **M2-4**  | F-series iterations: three-layer memory (F47/F49), query understanding & strategy routing (F40), frontend architecture & Agent trace & security (F88), and 100+ more fixes |
| **M2-5**  | Itinerary budget breakdown output optimization                                                                                                                             |

Each module also has a **business development record** markdown documenting design decisions, implementation details, and self-review results — a key academic artifact of the thesis.

---

## 12. Scripts Reference

| Script                  | Purpose                                                                      |
| ----------------------- | ---------------------------------------------------------------------------- |
| `init_all.sh`           | One-shot init: MySQL schema, ES index + IK, Milvus collection, MinIO buckets |
| `init_mysql.sql`        | DDL for all business tables                                                  |
| `init_elasticsearch.sh` | Creates the attraction index with the IK Chinese analyzer                    |
| `install_es_ik.sh`      | Installs the IK analyzer plugin into ES                                      |
| `init_milvus.py`        | Creates the vector collection in Milvus                                      |
| `init_minio.sh`         | Creates required MinIO buckets                                               |
| `init_redis.sh`         | Redis readiness check / basic config                                         |
| `crawl_attractions.py`  | Seeds attraction data from public sources                                    |
| `regression/`           | Regression test harness for the agent pipeline                               |
| `data/`                 | Local data artifacts used by scripts                                         |

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
Query the `agent_trace` table (latest first) for the full execution audit trail of any request.

**Q: How do I switch to a different LLM?**  
`spring-ai` supports multiple providers. Swap the DashScope starter for the corresponding Spring AI starter (e.g. OpenAI) and change the model config in `application.yml` — the agent framework and RAG pipeline are provider-agnostic.

---

## License

All rights reserved. This project is a personal thesis work; please contact the author before reuse.
