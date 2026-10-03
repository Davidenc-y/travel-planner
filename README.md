# Travel Planner — AI-Powered Intelligent Travel Planning System

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green)
![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.2.0-blue)
![Next.js](https://img.shields.io/badge/Next.js-14.2.29-black)
![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)

> A full-stack graduate project combining **Large Language Models**, **Multi-Agent Collaboration**, **Retrieval-Augmented Generation (RAG)**, and **Graph Workflow Orchestration** to deliver personalized, dynamic, and explainable travel planning.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Module Breakdown](#3-module-breakdown)
4. [Core Capabilities](#4-core-capabilities)
5. [Technology Stack](#5-technology-stack)
6. [Database Design](#6-database-design)
7. [Performance & Testing](#7-performance--testing)
8. [Getting Started](#8-getting-started)
9. [Project Status](#9-project-status)

---

## 1. Project Overview

### What It Does

The system provides an intelligent travel planning assistant that:

- **Plans** multi-day itineraries via multi-agent supervisor workflow (preference analysis → attraction retrieval → route optimization → budget estimation)
- **Retrieves** real attraction data via hybrid RAG (BM25 keyword search + KNN vector search + RRF fusion + optional reranking)
- **Remembers** user preferences across sessions via session-context memory (chunked storage, heuristic consolidation, distillation)
- **Explains** its recommendations with grounding citations linking back to source attractions
- **Streams** responses via SSE for real-time interaction with interruption support

### Key Differentiators

| Capability | Implementation |
|---|---|
| Multi-agent planning | Supervisor pattern with sub-agents for preference, attraction, routing, budget |
| Hybrid retrieval | BM25 + KNN dual-path with RRF fusion, optional HyDE rewriting and LLM query expansion |
| Memory persistence | Session knowledge chunks with heuristic consolidation and LLM distillation |
| Grounding & citations | Attraction references with `[n]` markers linked to retrieved sources |
| Concurrent query coalescing | Identical concurrent searches share a single computation via in-flight registry |
| Virtual threads | Java 21 virtual threads for non-blocking request handling |
| Multi-instance HA | Nacos service discovery + Spring Cloud LoadBalancer for horizontal scaling |

---

## 2. Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Next.js Frontend                          │
│  (Chat UI / Attractions / Itinerary / Admin / Profile / Share) │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP / SSE
┌────────────────────────────▼────────────────────────────────────┐
│                    Stream Gateway (:8083)                        │
│         (SSE tunneling, auth passthrough, rate limiting)        │
└──────────┬─────────────────────────────────────────┬────────────┘
           │                                         │
┌──────────▼──────────┐              ┌───────────────▼────────────┐
│  Planning (:8081)    │              │     Knowledge (:8082)       │
│  ┌────────────────┐  │              │  ┌────────────────────┐   │
│  │ Chat-Domain    │  │  Feign/LB    │  │ RAG Pipeline        │   │
│  │ (Supervisor    │──┼─────────────►│  │ (QU→HyDE→BM25+KNN  │   │
│  │  Multi-Agent)  │  │              │  │  →RRF→Rerank)       │   │
│  │ Memory/Pipeline│  │              │  │ ETL (MySQL→ES+     │   │
│  │ Controllers    │  │              │  │  Milvus)             │   │
│  └────────────────┘  │              │  └────────────────────┘   │
└──────────┬──────────┘              └───────────────┬────────────┘
           │                                         │
┌──────────▼─────────────────────────────────────────▼────────────┐
│                       AI Gateway (Library)                        │
│    (Model Registry, Role Routing, Circuit Breaker,               │
│     LLM Budget Guard, DashScope/OpenAI Provider Adapter)        │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────┐
        │ DashScope │  │  Milvus  │  │Elasticsearch│
        │  (LLM)    │  │ (Vector) │  │  (Search)  │
        └──────────┘  └──────────┘  └──────────┘
```

### Communication Patterns

- **Frontend → Gateway**: HTTP + SSE (Server-Sent Events for streaming chat)
- **Gateway → Planning**: HTTP passthrough with JWT auth
- **Planning → Knowledge**: Spring Cloud OpenFeign with Nacos service discovery + LoadBalancer (multi-instance)
- **Planning/Knowledge → AI Models**: AI Gateway library (in-process) with model registry and circuit breaker
- **Event Bus**: Redis pub/sub for cache invalidation, Redis Stream for writeback events
- **Configuration**: Nacos config center (namespace per environment, group per service)

---

## 3. Module Breakdown

### Backend Modules (Maven Multi-Module)

| Module | Port | Description | Key Packages |
|---|---|---|---|
| `travel-core` | — | Shared domain entities, utilities | `entity`, `guard` |
| `travel-common` | — | Cross-cutting concerns | `config`, `event`, `result`, `util`, `web` |
| `travel-ai-gateway` | — | LLM provider abstraction (library) | `core` (registry/factory), `route` (role routing, budget guard) |
| `travel-memory` | — | Session memory management (library) | `repository`, `service` |
| `travel-chat-stream` | — | SSE streaming utilities (library) | `service` |
| `travel-chat-domain` | — | Multi-agent chat domain logic | `agent` (supervisor), `memory` (pipeline, knowledge), `cancellation` |
| `travel-knowledge` | 8082 | Knowledge & retrieval service | `rag` (strategy, service, support), `etl`, `controller` |
| `travel-planning` | 8081 | Trip planning service (main) | `service`, `controller`, `config` |
| `travel-stream-gateway` | 8083 | SSE gateway | `gateway`, `sentinel` |
| `travel-crawl` | 8087 | Data crawler (AMap + enrichment) | `crawl`, `pipeline`, `detail` |

### Frontend (Next.js 14 App Router)

| Route | Description |
|---|---|
| `/` | Landing page |
| `/login`, `/register` | Authentication |
| `/chat` | Main chat interface (SSE streaming, session management) |
| `/attractions` | Attraction browsing & search |
| `/itinerary` | Itinerary management (versions, map, export) |
| `/plan` | Trip planning interface |
| `/profile` | User profile & preferences |
| `/admin` | Admin dashboard (reliability, metrics) |
| `/share` | Shared itinerary view |

### Key Components

- **Chat**: `ChatPageContent`, `MessageBubble` (with grounding citations), `Composer` (with preference tags), `SessionList`
- **Itinerary**: `ItineraryMap` (Leaflet), `BudgetSection` (recharts pie), `ItineraryCard`, `VersionDialog`
- **Admin**: Reliability dashboard (Sentinel rules, event consumers, RAG quality metrics)

---

## 4. Core Capabilities

### 4.1 Multi-Agent Supervisor Workflow

The chat planning pipeline uses a supervisor pattern where a coordinator routes to specialized sub-agents:

1. **Preference Analysis Agent** — extracts user preferences (city, days, budget, interests)
2. **Attraction Retrieval Agent** — queries knowledge service via RAG
3. **Route Planning Agent** — generates day-by-day itinerary with time slots
4. **Budget Estimation Agent** — calculates costs (tickets, food, transport, accommodation)
5. **Quality/Critic Agent** — validates output against grounding data

The supervisor iterates until convergence or budget limits (`max-steps`, `wall-ms`, `max-tokens` per preset).

### 4.2 Hybrid RAG Pipeline

```
User Query
    │
    ▼
Query Understanding (QU)
    ├── Intent classification (city, type, budget, keywords)
    └── LRU cache (256 entries)
    │
    ▼ (if HyDE enabled)
HyDE Rewriting
    └── LLM generates hypothetical answer → vector query text
    └── LRU cache
    │
    ▼
Dual-Path Retrieval (parallel)
    ├── BM25: Elasticsearch full-text search
    └── KNN: Milvus vector search (HNSW, ef-query=64)
    └── Per-path timeout (4s)
    │
    ▼
RRF Fusion
    └── Reciprocal Rank Fusion merges results
    │
    ▼ (if enabled)
Reranking
    └── DashScope GTE reranker with confidence thresholds
    │
    ▼
Results + Grounding Citations
```

**Performance optimizations**:
- **Query coalescing**: Concurrent identical queries share one computation via in-flight `CompletableFuture` registry
- **Query result cache**: TTL-based result cache (60s default, LRU 256)
- **Query vector cache**: Embedding vector LRU cache (avoids re-embedding identical queries)
- **Embedding semaphore**: Concurrency limit (default 8) with fail-open to BM25-only on overflow

### 4.3 Session Memory

- **Chunked storage**: Conversation history stored as typed chunks (`itinerary_day`, `preference`, `fact`) in Milvus + ES
- **Consolidation**: Heuristic deduplication of similar chunks (light model judgment)
- **Distillation**: LLM-generated semantic summaries and entity extraction stored with `distill:` prefix
- **Persistence**: `t_consolidation_ledger` table for cross-restart durability (fail-open dual-write)

### 4.4 Observability & Guard Rails

| Mechanism | Description |
|---|---|
| LLM Budget Guard | Daily/hourly token limits (3M/600K default) with Redis shared accounting |
| Model Circuit Breaker | Per-model failure detection with half-open recovery |
| Sentinel Flow Rules | Per-endpoint QPS limits with warm-up, concurrent thread limits for chat |
| Agent Trace | Full request tracing to `t_agent_trace` (model, tokens, latency, grounding) |
| RagRoutingMetrics | Micrometer counters for QU cache, embedding cache, dispatch routing, degradation |
| Admin Dashboard | `/admin/reliability` exposes consumer status, RAG quality, gray release snapshot |

### 4.5 High Availability

- **Multi-instance**: Nacos service discovery + Spring Cloud LoadBalancer (verified dual-instance with automatic failover)
- **Graceful degradation**: RAG fails → BM25-only; embedding fails → lexical search; LLM fails → circuit breaker opens
- **Self-recovery**: Under extreme load, guards trigger timeout/degradation → system recovers in 12-40 seconds without restart
- **Thin-city precheck**: Cities with insufficient corpus data (<10 attractions) get template response (zero LLM cost) instead of empty graph runs

---

## 5. Technology Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 (Virtual Threads) | Runtime with Loom lightweight concurrency |
| Spring Boot | 3.5.0 | Application framework |
| Spring AI Alibaba | 1.1.2.0 | LLM integration framework |
| Spring Cloud | 2025.0.0.0 | Microservice toolchain (OpenFeign, LoadBalancer) |
| Nacos | 2.x | Service discovery + configuration center |
| Sentinel | 1.8.9 | Rate limiting + circuit breaking |
| MySQL | 8.0 | Primary relational database |
| Elasticsearch | 7.17 | Full-text search (BM25) |
| Milvus | 2.3 | Vector database (HNSW) |
| Redis | 7.x | Caching, event bus, shared counters |
| RabbitMQ | 3.x | Message queue (optional, eventbus=redis default) |
| MyBatis-Plus | 3.5 | ORM with pagination |
| DashScope | — | LLM provider (qwen series) |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| Next.js | 14.2.29 | React framework (App Router) |
| React | 18 | UI library |
| Tailwind CSS | 3 | Styling |
| Leaflet | — | Interactive maps |
| recharts | — | Charts (budget pie) |
| markmap-view | — | Mind map visualization |
| Vitest | — | Unit testing |

### Infrastructure

| Technology | Purpose |
|---|---|
| Docker | MySQL, ES, Milvus, Redis, RabbitMQ, Nacos, MinIO |
| Git | Version control |
| Maven | Build tool (multi-module reactor) |

---

## 6. Database Design

### Core Tables (20 tables)

| Table | Purpose |
|---|---|
| `t_user` | User accounts |
| `t_chat_session` | Chat sessions |
| `t_chat_message` | Chat messages |
| `t_chat_message_idem` | Idempotency keys for message dedup |
| `t_attraction` | Attraction data (2,732 records, 46% with descriptions) |
| `t_itinerary` | Generated itineraries |
| `t_itinerary_version` | Itinerary version history |
| `t_itinerary_task_snapshot` | Task snapshots for resume |
| `t_travel_profile` | User travel preferences |
| `t_user_behavior_profile` | Behavioral analytics |
| `t_user_profile_slot` | Profile version slots |
| `t_user_model_usage` | Model usage tracking |
| `t_agent_trace` | Full request traces (model, tokens, latency, grounding) |
| `t_system_config` | System configuration |
| `t_etl_outbox` | ETL change tracking for ES/Milvus sync |
| `t_consolidation_ledger` | Memory consolidation persistence |
| `t_attraction_visit` | Visit tracking |
| `graph_node` | Knowledge graph nodes (88 nodes, archived) |
| `graph_edge` | Knowledge graph edges (109 edges, archived) |

### Search Indices

| Store | Index/Collection | Purpose |
|---|---|---|
| Elasticsearch | `attraction_index` | BM25 full-text search (2,732 docs) |
| Elasticsearch | `session_context` | Session memory chunks |
| Milvus | `attraction_vectors` | KNN vector search (HNSW, L2, dim=1536) |
| Milvus | `session_context` | Session memory vectors |

---

## 7. Performance & Testing

### Test Coverage

| Suite | Count | Status |
|---|---|---|
| Backend reactor tests | 1,468 | All pass (0 failures, 0 errors) |
| Frontend Vitest | 291 | All pass |
| TypeScript strict | — | Zero errors |
| Production build | — | 88.1 kB shared JS |

### Comprehensive Real-Environment Testing

| Category | Tests | Result |
|---|---|---|
| Correct execution | Login, search, session, streaming chat, admin | All pass |
| Boundary execution | Empty query, invalid params, long input, non-existent resources | All pass (graceful 400) |
| Extreme environment | 5 concurrent searches, 10 rapid health checks, full planning round | **All pass** (10/10) |
| Error execution | Invalid token, 404, malformed JSON, auth failures | All pass |

### Performance Metrics

| Metric | Value |
|---|---|
| Hot search latency (cached) | ~2ms median |
| Cold search latency | ~2.4s p50, ~4.5s p90 (QU LLM path) |
| Chat streaming round | 7-45s (depends on itinerary complexity) |
| Concurrent search handling | 5+ simultaneous without degradation |
| Self-recovery after extreme load | 12-40 seconds |
| Token budget enforcement | 3M daily / 600K hourly (Redis shared) |

### Data Corpus

| Metric | Value |
|---|---|
| Total attractions | 2,732 |
| With descriptions | 1,258 (46.0%) |
| Cities covered | 18+ (QU cities) |
| Knowledge graph | 88 nodes / 109 edges (archived, pending 70% coverage) |
| Data sources | AMap POI + Tavily web enrichment + manual entry |

---

## 8. Getting Started

### Prerequisites

- Java 21, Maven 3.9+
- Node.js 18+, npm
- Docker (for middleware: MySQL, ES, Milvus, Redis, Nacos)

### Infrastructure Setup

```bash
# Start middleware via Docker Compose
docker-compose up -d

# Initialize database schema
mysql -u root -p < scripts/init_mysql.sql

# Initialize Elasticsearch indices
bash scripts/init_elasticsearch.sh

# Initialize Milvus collections
python scripts/init_milvus.py
```

### Backend Build & Run

```bash
# Build all modules
mvn clean install -DskipTests

# Start services (order matters)
java -jar travel-knowledge/target/travel-knowledge-1.0-SNAPSHOT.jar
java -jar travel-planning/target/travel-planning-1.0-SNAPSHOT.jar
java -jar travel-stream-gateway/target/travel-stream-gateway-1.0-SNAPSHOT.jar
```

### Frontend

```bash
cd travel-frontend/next-app
npm install
npm run dev        # development
npm run build      # production
```

### Environment Variables

| Variable | Description | Required |
|---|---|---|
| `DASHSCOPE_API_KEY` | DashScope LLM API key | Yes |
| `AMAP_WEB_API_KEY` | AMap Web API key (maps, crawling) | Yes |
| `TAVILY_API_KEY` | Tavily search API key (enrichment) | Optional |
| `JWT_SECRET` | JWT signing secret | Yes |
| `NACOS_ADDR` | Nacos server address | Production |
| `MYSQL_HOST` / `REDIS_HOST` | Middleware hosts | Production |

---

## 9. Project Status

### Current Version: v2.0.7.38

### Development Milestones

| Version | Key Deliverables |
|---|---|
| v1.x | Core functionality: auth, chat, RAG, itinerary, frontend |
| v2.0.0 | Modular architecture (11 Maven modules), microservices split |
| v2.0.1-v2.0.4 | Memory system, RAG enhancements, GraphRAG experiment, frontend map/dashboard |
| v2.0.5 | Structural improvements, LLM budget guard, virtual threads |
| v2.0.6 | Module independence, chat streaming convergence, package reorganization |
| v2.0.7.1-v2.0.7.10 | RAG quality (Hit@5 0.8125), TTFT optimization, webflux retirement |
| v2.0.7.11-v2.0.7.20 | Tavily quota management, GraphRAG archive (coverage prerequisite) |
| v2.0.7.21-v2.0.7.31 | Multi-agent robustness, HA multi-instance, Sentinel governance, corpus expansion |
| v2.0.7.32-v2.0.7.38 | Embedding optimization, concurrent search coalescing, GM-5 resolution, 10/10 test score |

### Quality Summary

- **1,468 backend tests** + **291 frontend tests** = zero failures
- **10/10 comprehensive real-environment test** (correct/boundary/extreme/error)
- **Zero TODO/FIXME** in production code
- **TypeScript strict mode** with zero errors

### Known Limitations & Future Work

| Item | Status |
|---|---|
| Description coverage (46% → 70% target) | Blocked by Tavily API quota; resume when available |
| GraphRAG decision gate | Waiting for 70% coverage prerequisite |
| Knowledge service under extreme concurrent load | Mitigated (self-recovers 12-40s); full resolution requires architectural change |

---

## License

All Rights Reserved
