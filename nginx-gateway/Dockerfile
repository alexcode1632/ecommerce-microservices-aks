FROM nginx:1.27-alpine

# Eliminar configuración por defecto
RUN rm /etc/nginx/conf.d/default.conf

# Copiar configuración del API Gateway
COPY nginx.conf /etc/nginx/conf.d/gateway.conf

# Los certificados TLS se montan desde un Secret de Kubernetes en /etc/nginx/ssl/
# Para build local de prueba, copiar tls.crt y tls.key manualmente aquí
# COPY ssl/tls.crt /etc/nginx/ssl/tls.crt
# COPY ssl/tls.key /etc/nginx/ssl/tls.key

RUN mkdir -p /etc/nginx/ssl

EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
