FROM nginx:1.27-alpine

# Eliminar configuración por defecto
RUN rm /etc/nginx/conf.d/default.conf

# Copiar configuración del servidor web
COPY nginx.conf /etc/nginx/conf.d/webserver.conf

# Copiar archivos del frontend (para imagen de Docker local/pruebas)
# En AKS estos archivos vienen del PVC (Azure Files)
COPY html/ /usr/share/nginx/html/

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
