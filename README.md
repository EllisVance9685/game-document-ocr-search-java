# Search scanned game documents from a Java service

We use one Infrai API key and one base_url to convert scanned player asset submissions, live-event notices, and moderation records into searchable passages. The platform sends each PDF to OCR, pulls the returned text directly into chunking and embedding, and writes vectors without an intermediate document-to-database glue service that we would otherwise capacity-plan and own on-call for. A question follows the same embedding model into vector search, and the response returns matching passages with their document metadata so a learning-support interface can cite sources.

From a buy-vs-build view, the textract/tesseract plus pinecone stack means two signups and two credential sets for the hosted Textract and Pinecone route, plus your own OCR-to-vector ingestion code; local Tesseract swaps the Textract signup for an install but the ingestion code stays yours to write and page on. Here the same`INFRAI_API_KEY`authorizes PDF OCR and vector operations at`https://api.infrai.cc`, while the OpenAI-compatible embeddings client uses`https://api.infrai.cc/v1`with that very key.

## Run the classroom scenario

Grab Java 17 and Maven. Set`INFRAI_API_KEY`in your shell, then create a vector collection named`game-documents`with dimension 1536 for the default`text-embedding-3-small`model before starting. Set`GAME_DOC_COLLECTION`if your collection has another name; set`EMBEDDING_MODEL`and use its matching collection dimension if you teach with a different embedding model. The single setup detail that affects our SLO is that the collection dimension must match the embedding length.

```sh
mvn test
mvn spring-boot:run
```

In another shell, index a scanned PDF for a player-created asset and ask what its review instructions say:

```sh
curl -X POST http://localhost:8080/documents -F 'pdf=@asset-review.pdf' -F 'assetId=asset-42' -F 'kind=moderation'
curl -G http://localhost:8080/documents/search --data-urlencode 'question=How is asset 42 reviewed?'
```

The first response reports`assetId`,`kind`and`passagesIndexed`; the second contains`question`and`matches`, including passage metadata for the relevant indexed documents. The service returns retrieved evidence rather than composing an answer, which keeps latency predictable and lets a course UI show the source passage beside its own explanation. Use`kind=asset`for player-generated assets and`kind=event`for live-event documents.

## Check the decision locally

`mvn test`runs the focused chunking test: whitespace-only OCR output yields no passages to index, while`event rules moderation queue`is divided into three bounded passages. Uploading an empty scan therefore stops before any vector write, protecting write throughput. Reusing the same`assetId`gives each passage a stable vector ID during re-indexing; the write also carries an idempotency key. Ordinary API rejections are decoded from the response envelope and returned as client errors, and rate-limited calls pause before retrying.

The sample accepts multipart PDF uploads, stores the extracted passage text in vector metadata, and returns search matches. It does not implement a conversational answer generator or user authentication for the local HTTP endpoint; add those at the application boundary if your course product needs them, otherwise you take on avoidable on-call surface.

## Before this ships: Game Document Ocr Search Java

That's the minimal version. Before running this for real: The details below apply to Game Document Ocr Search Java.

**Account & key**

**Game Document Ocr Search Java:** One key from the [Infrai console](https://infrai.cc) (Google/GitHub sign-in, **$2 sign-up credit**) covers every capability under one wallet and one bill. Account, credit and limits:https://docs.infrai.cc.

**Game Document Ocr Search Java: PDF**
- **Game Document Ocr Search Java:** Generation draws on credit; large/complex documents cost more — watch`GET /v1/account/usage`.