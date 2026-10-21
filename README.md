# E-Commerce Platform — Microservices on Azure Kubernetes Service

A cloud-native e-commerce back end that evolved from a Java monolith into **three independent microservices**, containerized with **Docker** and deployed on **Azure Kubernetes Service (AKS)** behind an **Nginx API gateway** with TLS termination.

Built for the *Distributed Systems* course at ESCOM–IPN (Feb–Jun 2026).

## Architecture

```
Browser ──HTTPS──► Azure LoadBalancer ──► Nginx API Gateway (TLS termination, HTTP→HTTPS redirect)
                                              ├── /usuarios/*   → usuarios-svc   (ClusterIP)
                                              ├── /articulos/*  → articulos-svc  (ClusterIP)
                                              ├── /compras/*    → compras-svc    (ClusterIP)
                                              └── /             → webserver-svc  (static front end on Azure Files)

Each microservice ──► its own MySQL database (Azure Database for MySQL, PaaS)
```

| Service | Responsibility | Endpoints |
|---|---|---|
| **Users** | Login with token issuance, access verification, user CRUD | `login`, `verifica_acceso`, `alta_usuario`, `consulta_usuario`, `modifica_usuario`, `borra_usuario` |
| **Products** | Product catalog and keyword search | `alta_articulo`, `consulta_articulos`, `consulta_articulo_por_id` |
| **Orders** | Shopping cart and purchases with stock control | `alta_articulo`, `compra_articulo`, `consulta_carrito_compra`, `elimina_articulo_carrito_compra`, `elimina_carrito_compra` |

## Key design decisions

- **Database per service:** each microservice owns its schema, so services can be deployed and scaled independently. Services communicate over internal HTTP.
- **ACID transactions:** multi-table writes (e.g., purchase → update stock + cart) run inside JDBC transactions with commit/rollback to keep data consistent.
- **Token-based access:** every protected endpoint validates the user's session token before executing.
- **Secrets out of the code:** database credentials are injected at runtime from Kubernetes Secrets as environment variables. No credentials or private keys are stored in this repository.
- **Single entry point:** the Nginx gateway handles TLS (TLSv1.2/1.3), redirects HTTP to HTTPS and routes by path to ClusterIP services that are not exposed publicly.
- **Multi-stage Docker builds:** Maven compiles the WAR in a build stage; the runtime image contains only Tomcat and the artifact.

## Tech stack

Java 11 · JAX-RS (Jersey) · Apache Tomcat 9 · Maven · MySQL · Docker · Kubernetes (AKS) · Azure Container Registry · Azure Files · Nginx · JavaScript front end

## Repository structure

```
microservicios/
  usuarios/     Java service + Dockerfile
  articulos/    Java service + Dockerfile
  compras/      Java service + Dockerfile
nginx-gateway/  API gateway config + Dockerfile (certificates are generated locally, not committed)
webserver/      Static front end served by Nginx
k8s/            Deployments, Services, PV/PVC and Secret templates
sql/            Database schemas for each service
docs/           Step-by-step Azure deployment guide (Spanish)
```

## Running it

The full deployment guide (Azure resources, image builds, certificate generation and `kubectl` steps) is in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

Quick outline:

1. Create the Azure resources (AKS, ACR, MySQL PaaS, Azure Files) and load the schemas in `sql/`.
2. Build and push each image to ACR: `docker build -t <acr>.azurecr.io/usuarios:latest microservicios/usuarios && docker push ...`
3. Create the secrets (values are never committed):
   ```bash
   kubectl create secret generic db-secret \
     --from-literal=DB_HOST=<host> --from-literal=DB_USER=<user> --from-literal=DB_PASSWORD=<password>
   kubectl create secret tls gateway-tls-secret --cert=tls.crt --key=tls.key
   ```
4. Apply the manifests: `kubectl apply -f k8s/`

## Testing

All endpoints were validated with `curl` test cases covering success and error paths (HTTP 200 / 400 responses, invalid tokens, missing items).
