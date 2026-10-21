# Tarea 9 — E-Commerce con Microservicios en AKS

**Boleta:** 2023630200  
**Nomenclatura:** `t92023630200`

## Arquitectura

```
Navegador → HTTPS → LoadBalancer → Nginx Gateway (API GW)
                                        ├── /usuarios/* → usuarios-svc:8080 (ClusterIP)
                                        ├── /articulos/* → articulos-svc:8080 (ClusterIP)
                                        ├── /compras/*  → compras-svc:8080  (ClusterIP)
                                        └── /           → webserver-svc:80  (ClusterIP)
                                                               ↑
                                                          Azure Files (PV/PVC)

Microservicios → MySQL PaaS (t92023630200bd.mysql.database.azure.com)
  ├── Usuarios  → t92023630200bdgu
  ├── Artículos → t92023630200bdga
  └── Compras   → t92023630200bdgc
```

## Nomenclatura de recursos Azure

| Recurso | Nombre |
|---------|--------|
| MySQL PaaS | `t92023630200bd` |
| Azure Container Registry | `t92023630200acr` |
| Azure Files (cuenta) | `t92023630200caf` |
| Recurso compartido de archivos | `t92023630200rca` |
| Bases de datos | `t92023630200bdgu` / `t92023630200bdga` / `t92023630200bdgc` |

---

## Paso 0 — Prerrequisitos locales

```bash
# Herramientas necesarias
az --version          # Azure CLI
docker --version      # Docker
kubectl version       # kubectl
mvn --version         # Maven 3.9+
java -version         # Java 11+
openssl version       # OpenSSL
```

---

## Paso 1 — Crear recursos en Azure

```bash
# Variables de entorno (ajustar según sea necesario)
RESOURCE_GROUP="rg-tarea9"
LOCATION="eastus"
ACR_NAME="t92023630200acr"
AKS_NAME="aks-tarea9"
MYSQL_NAME="t92023630200bd"
STORAGE_ACCOUNT="t92023630200caf"
FILE_SHARE="t92023630200rca"

# Login
az login

# Grupo de recursos
az group create --name $RESOURCE_GROUP --location $LOCATION

# Azure Container Registry
az acr create \
  --resource-group $RESOURCE_GROUP \
  --name $ACR_NAME \
  --sku Basic \
  --admin-enabled true

# AKS (adjuntar ACR para pull de imágenes sin credenciales)
az aks create \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_NAME \
  --node-count 2 \
  --node-vm-size Standard_B2s \
  --attach-acr $ACR_NAME \
  --generate-ssh-keys

# Credenciales para kubectl
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_NAME

# MySQL Flexible Server (PaaS con IP pública, RNF-9)
az mysql flexible-server create \
  --resource-group $RESOURCE_GROUP \
  --name $MYSQL_NAME \
  --location $LOCATION \
  --admin-user adminuser \
  --admin-password "<YOUR_DB_PASSWORD>" \
  --sku-name Standard_B1ms \
  --tier Burstable \
  --public-access 0.0.0.0

# Cuenta de almacenamiento para Azure Files
az storage account create \
  --resource-group $RESOURCE_GROUP \
  --name $STORAGE_ACCOUNT \
  --location $LOCATION \
  --sku Standard_LRS \
  --kind StorageV2

# Recurso compartido de archivos
az storage share create \
  --account-name $STORAGE_ACCOUNT \
  --name $FILE_SHARE \
  --quota 1
```

---

## Paso 2 — Crear bases de datos

```bash
MYSQL_HOST="${MYSQL_NAME}.mysql.database.azure.com"
MYSQL_USER="adminuser"

# Crear las tres bases de datos
mysql -h $MYSQL_HOST -u $MYSQL_USER -p < sql/usuarios_db.sql
mysql -h $MYSQL_HOST -u $MYSQL_USER -p < sql/articulos_db.sql
mysql -h $MYSQL_HOST -u $MYSQL_USER -p < sql/compras_db.sql
```

---

## Paso 3 — Generar certificado TLS (RNF-15)

> Ejecutar desde la carpeta `nginx-gateway/ssl/`
> Ver instrucciones detalladas en `nginx-gateway/ssl/README_CERTIFICADO.txt`

```bash
cd nginx-gateway/ssl

# CA raíz
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
  -subj "/C=MX/ST=CDMX/O=Tarea9CA/CN=Tarea9RootCA"

# Cert del servidor (reemplazar <IP_LB> con la IP del LoadBalancer obtenida en Paso 5)
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr \
  -subj "/C=MX/ST=CDMX/O=Tarea9/CN=<IP_LB>"
openssl x509 -req -days 365 \
  -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt

# Concatenar cadena de certificados
cat server.crt ca.crt > tls.crt
cp server.key tls.key

cd ../..
```

> **Nota:** Si aún no tienes la IP del LoadBalancer, puedes usar un placeholder y
> regenerar el certificado después de aplicar `svc-gateway.yaml` (Paso 6).

---

## Paso 4 — Build y push de imágenes Docker

```bash
ACR_LOGIN_SERVER="${ACR_NAME}.azurecr.io"

# Login al ACR
az acr login --name $ACR_NAME

# Microservicio Usuarios
docker build -t ${ACR_LOGIN_SERVER}/usuarios:latest microservicios/usuarios/
docker push ${ACR_LOGIN_SERVER}/usuarios:latest

# Microservicio Artículos
docker build -t ${ACR_LOGIN_SERVER}/articulos:latest microservicios/articulos/
docker push ${ACR_LOGIN_SERVER}/articulos:latest

# Microservicio Compras
docker build -t ${ACR_LOGIN_SERVER}/compras:latest microservicios/compras/
docker push ${ACR_LOGIN_SERVER}/compras:latest

# Servidor web (frontend)
docker build -t ${ACR_LOGIN_SERVER}/webserver:latest webserver/
docker push ${ACR_LOGIN_SERVER}/webserver:latest

# API Gateway
docker build -t ${ACR_LOGIN_SERVER}/gateway:latest nginx-gateway/
docker push ${ACR_LOGIN_SERVER}/gateway:latest
```

---

## Paso 5 — Subir archivos del frontend a Azure Files

```bash
STORAGE_KEY=$(az storage account keys list \
  --resource-group $RESOURCE_GROUP \
  --account-name $STORAGE_ACCOUNT \
  --query "[0].value" -o tsv)

# Subir archivos del frontend al recurso compartido
az storage file upload \
  --account-name $STORAGE_ACCOUNT \
  --account-key $STORAGE_KEY \
  --share-name $FILE_SHARE \
  --source webserver/html/prueba.html

az storage file upload \
  --account-name $STORAGE_ACCOUNT \
  --account-key $STORAGE_KEY \
  --share-name $FILE_SHARE \
  --source webserver/html/WSClient.js

# Si tienes usuario_sin_foto.png (imagen placeholder)
# az storage file upload --account-name $STORAGE_ACCOUNT --account-key $STORAGE_KEY \
#   --share-name $FILE_SHARE --source webserver/html/usuario_sin_foto.png
```

---

## Paso 6 — Desplegar en Kubernetes

```bash
# ── Secrets ──────────────────────────────────────────────────────────────────
# Secret de base de datos
kubectl create secret generic db-secret \
  --from-literal=DB_HOST="${MYSQL_NAME}.mysql.database.azure.com" \
  --from-literal=DB_USER="adminuser" \
  --from-literal=DB_PASSWORD="<YOUR_DB_PASSWORD>"

# Secret de Azure Files para el PV
STORAGE_KEY=$(az storage account keys list \
  --resource-group $RESOURCE_GROUP \
  --account-name $STORAGE_ACCOUNT \
  --query "[0].value" -o tsv)

kubectl create secret generic azure-files-secret \
  --from-literal=azurestorageaccountname=$STORAGE_ACCOUNT \
  --from-literal=azurestorageaccountkey=$STORAGE_KEY

# Secret TLS del gateway (después de generar el certificado en Paso 3)
kubectl create secret tls gateway-tls-secret \
  --cert=nginx-gateway/ssl/tls.crt \
  --key=nginx-gateway/ssl/tls.key

# ── Almacenamiento ────────────────────────────────────────────────────────────
# Editar k8s/pv.yaml: reemplazar <RESOURCE_GROUP> con el valor real
sed -i "s/<RESOURCE_GROUP>/$RESOURCE_GROUP/g" k8s/pv.yaml

kubectl apply -f k8s/pv.yaml
kubectl apply -f k8s/pvc.yaml

# ── Microservicios ────────────────────────────────────────────────────────────
kubectl apply -f k8s/deploy-usuarios.yaml
kubectl apply -f k8s/svc-usuarios.yaml

kubectl apply -f k8s/deploy-articulos.yaml
kubectl apply -f k8s/svc-articulos.yaml

kubectl apply -f k8s/deploy-compras.yaml
kubectl apply -f k8s/svc-compras.yaml

# ── Servidor web ──────────────────────────────────────────────────────────────
kubectl apply -f k8s/deploy-webserver.yaml
kubectl apply -f k8s/svc-webserver.yaml

# ── API Gateway (LoadBalancer) ────────────────────────────────────────────────
kubectl apply -f k8s/deploy-gateway.yaml
kubectl apply -f k8s/svc-gateway.yaml

# Obtener la IP pública del LoadBalancer (esperar ~2 min)
kubectl get svc gateway-svc --watch
```

---

## Paso 7 — Actualizar certificado con IP real (si se usó placeholder)

```bash
# Una vez obtenida la IP del LoadBalancer (p.e. 20.x.x.x):
IP_LB="<IP_PUBLICA_DEL_LOADBALANCER>"

cd nginx-gateway/ssl
openssl req -new -key server.key -out server.csr \
  -subj "/C=MX/ST=CDMX/O=Tarea9/CN=${IP_LB}"
openssl x509 -req -days 365 \
  -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt
cat server.crt ca.crt > tls.crt
cd ../..

# Reemplazar el secret TLS
kubectl delete secret gateway-tls-secret
kubectl create secret tls gateway-tls-secret \
  --cert=nginx-gateway/ssl/tls.crt \
  --key=nginx-gateway/ssl/tls.key

# Reiniciar el gateway para que tome el nuevo cert
kubectl rollout restart deployment gateway-deploy
```

---

## Paso 8 — Verificación

```bash
# Ver estado de todos los pods
kubectl get pods

# Ver logs de un microservicio
kubectl logs -l app=usuarios --tail=50
kubectl logs -l app=articulos --tail=50
kubectl logs -l app=compras --tail=50
kubectl logs -l app=gateway --tail=50

# Probar endpoints desde dentro del clúster
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
# Dentro del pod:
# curl http://usuarios-svc:8080/Usuarios/rest/ws/login?email=x&password=y
# exit

# Probar el gateway desde fuera (aceptar cert de CA propia)
IP_LB="<IP_PUBLICA_DEL_LOADBALANCER>"
curl -k https://${IP_LB}/usuarios/rest/ws/login?email=x&password=y
# Con el cert de la CA:
curl --cacert nginx-gateway/ssl/ca.crt https://${IP_LB}/
```

---

## Comandos útiles

```bash
# Escalar un microservicio
kubectl scale deployment usuarios-deploy --replicas=2

# Ver todos los servicios
kubectl get svc

# Describir un pod con error
kubectl describe pod <nombre-del-pod>

# Ver PV y PVC
kubectl get pv,pvc

# Eliminar todos los recursos del namespace
kubectl delete all --all -n default
```

---

## URLs de los endpoints (desde el exterior vía HTTPS)

| Operación | Método | URL |
|-----------|--------|-----|
| Login | GET | `https://<IP>/usuarios/rest/ws/login` |
| Verifica acceso | GET | `https://<IP>/usuarios/rest/ws/verifica_acceso` |
| Alta usuario | POST | `https://<IP>/usuarios/rest/ws/alta_usuario` |
| Consulta usuario | GET | `https://<IP>/usuarios/rest/ws/consulta_usuario` |
| Modifica usuario | PUT | `https://<IP>/usuarios/rest/ws/modifica_usuario` |
| Borra usuario | DELETE | `https://<IP>/usuarios/rest/ws/borra_usuario` |
| Alta artículo | POST | `https://<IP>/articulos/rest/ws/alta_articulo` |
| Consulta artículos | GET | `https://<IP>/articulos/rest/ws/consulta_articulos` |
| Compra artículo | PUT | `https://<IP>/compras/rest/ws/compra_articulo` |
| Consulta carrito | GET | `https://<IP>/compras/rest/ws/consulta_carrito_compra` |
| Elimina artículo carrito | DELETE | `https://<IP>/compras/rest/ws/elimina_articulo_carrito_compra` |
| Elimina carrito | DELETE | `https://<IP>/compras/rest/ws/elimina_carrito_compra` |
| Frontend | GET | `https://<IP>/prueba.html` |

---

## Nota sobre RNF-15 — Certificado

El certificado TLS cumple RNF-15 de la siguiente manera:
- Se crea una **CA raíz propia** (`ca.crt`) — esta CA sí es auto-firmada (es la raíz de confianza).
- El certificado del **servidor** (`server.crt`) es firmado por la CA raíz — **no es auto-firmado**.
- El API Gateway presenta `tls.crt` = `server.crt + ca.crt` (cadena completa).
- Los clientes deben importar `ca.crt` para confiar en el certificado del servidor.
