package servicio;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Application;

import java.sql.*;
import javax.sql.DataSource;
import javax.naming.Context;
import javax.naming.InitialContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

@Path("ws")
public class Servicio extends Application {

  static DataSource pool = null;
  static {
    try {
      Context ctx = new InitialContext();
      pool = (DataSource) ctx.lookup("java:comp/env/jdbc/datasource_Compras");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static final ObjectMapper j =
      new ObjectMapper().setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"));

  static final HttpClient http = HttpClient.newHttpClient();

  static final String USUARIOS_URL = System.getenv().getOrDefault(
      "USUARIOS_URL", "http://usuarios-svc:8080/Usuarios/rest/ws");
  static final String ARTICULOS_URL = System.getenv().getOrDefault(
      "ARTICULOS_URL", "http://articulos-svc:8080/Articulos/rest/ws");

  // ── RF-BE-3.1 compras/alta_articulo ──────────────────────────────────────────
  // Llamado internamente desde articulos/alta_articulo
  @POST @Path("alta_articulo")
  @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
  public Response alta_articulo(AltaArticuloRequest req) throws Exception {

    if (!verificaAccesoRemoto(req.id_usuario, req.token))
      return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "INSERT INTO stock(id_articulo,cantidad) VALUES(?,?)");
      s.setInt(1, req.id_articulo);
      s.setInt(2, req.cantidad);
      s.executeUpdate();
      return Response.ok(j.writeValueAsString(new Respuesta("OK"))).build();
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── RF-BE-3.2 compras/compra_articulo ────────────────────────────────────────
  @PUT @Path("compra_articulo")
  @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
  public Response compra_articulo(CompraRequest compra) throws Exception {

    if (compra == null || compra.cantidad <= 0)
      return bad("La cantidad debe ser mayor a cero");
    if (!verificaAccesoRemoto(compra.id_usuario, compra.token))
      return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Verificar stock disponible
        PreparedStatement s1 = c.prepareStatement(
            "SELECT cantidad FROM stock WHERE id_articulo=?");
        s1.setInt(1, compra.id_articulo);
        ResultSet rs1 = s1.executeQuery();
        if (!rs1.next()) throw new Exception("El artículo no existe");
        int stock = rs1.getInt("cantidad");

        // RF-BE-3.2.3
        if (compra.cantidad > stock) {
          c.rollback();
          return Response.status(400)
              .entity(j.writeValueAsString(new Respuesta("No hay suficientes artículos en el stock")))
              .build();
        }

        // ¿Existe en carrito?
        PreparedStatement s2 = c.prepareStatement(
            "SELECT cantidad FROM carrito_compra WHERE id_usuario=? AND id_articulo=?");
        s2.setInt(1, compra.id_usuario);
        s2.setInt(2, compra.id_articulo);
        ResultSet rs2 = s2.executeQuery();

        if (!rs2.next()) {
          // RF-BE-3.2.1: insertar nuevo
          PreparedStatement s3 = c.prepareStatement(
              "INSERT INTO carrito_compra(id_usuario,id_articulo,cantidad) VALUES(?,?,?)");
          s3.setInt(1, compra.id_usuario);
          s3.setInt(2, compra.id_articulo);
          s3.setInt(3, compra.cantidad);
          s3.executeUpdate();
        } else {
          // RF-BE-3.2.2: sumar cantidad existente
          PreparedStatement s4 = c.prepareStatement(
              "UPDATE carrito_compra SET cantidad=cantidad+? WHERE id_usuario=? AND id_articulo=?");
          s4.setInt(1, compra.cantidad);
          s4.setInt(2, compra.id_usuario);
          s4.setInt(3, compra.id_articulo);
          s4.executeUpdate();
        }

        // Restar del stock
        PreparedStatement s5 = c.prepareStatement(
            "UPDATE stock SET cantidad=cantidad-? WHERE id_articulo=?");
        s5.setInt(1, compra.cantidad);
        s5.setInt(2, compra.id_articulo);
        s5.executeUpdate();

        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("OK"))).build();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── RF-BE-3.3 compras/elimina_articulo_carrito_compra ────────────────────────
  @DELETE @Path("elimina_articulo_carrito_compra") @Produces(MediaType.APPLICATION_JSON)
  public Response elimina_articulo_carrito_compra(
      @QueryParam("id_usuario")  int id_usuario,
      @QueryParam("id_articulo") int id_articulo,
      @QueryParam("token") String token) throws Exception {

    if (!verificaAccesoRemoto(id_usuario, token)) return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Obtener cantidad del carrito para devolver al stock
        PreparedStatement s1 = c.prepareStatement(
            "SELECT cantidad FROM carrito_compra WHERE id_usuario=? AND id_articulo=?");
        s1.setInt(1, id_usuario);
        s1.setInt(2, id_articulo);
        ResultSet rs = s1.executeQuery();
        if (!rs.next()) throw new Exception("El artículo no existe en el carrito de compra");
        int cant = rs.getInt("cantidad");

        // RF-BE-3.3.1: devolver al stock
        PreparedStatement s2 = c.prepareStatement(
            "UPDATE stock SET cantidad=cantidad+? WHERE id_articulo=?");
        s2.setInt(1, cant);
        s2.setInt(2, id_articulo);
        s2.executeUpdate();

        // RF-BE-3.3.1: eliminar del carrito
        PreparedStatement s3 = c.prepareStatement(
            "DELETE FROM carrito_compra WHERE id_usuario=? AND id_articulo=?");
        s3.setInt(1, id_usuario);
        s3.setInt(2, id_articulo);
        s3.executeUpdate();

        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("OK"))).build();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── RF-BE-3.4 compras/elimina_carrito_compra ─────────────────────────────────
  @DELETE @Path("elimina_carrito_compra") @Produces(MediaType.APPLICATION_JSON)
  public Response elimina_carrito_compra(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    if (!verificaAccesoRemoto(id_usuario, token)) return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Por cada artículo del carrito, devolver cantidad al stock (RF-BE-3.4.1 y RF-BE-3.4.2)
        PreparedStatement s1 = c.prepareStatement(
            "SELECT id_articulo,cantidad FROM carrito_compra WHERE id_usuario=?");
        s1.setInt(1, id_usuario);
        ResultSet rs = s1.executeQuery();
        while (rs.next()) {
          PreparedStatement su = c.prepareStatement(
              "UPDATE stock SET cantidad=cantidad+? WHERE id_articulo=?");
          su.setInt(1, rs.getInt("cantidad"));
          su.setInt(2, rs.getInt("id_articulo"));
          su.executeUpdate();
        }

        // Borrar solo el carrito del usuario indicado
        PreparedStatement s2 = c.prepareStatement(
            "DELETE FROM carrito_compra WHERE id_usuario=?");
        s2.setInt(1, id_usuario);
        s2.executeUpdate();

        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("OK"))).build();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── consulta_carrito_compra (requerido por frontend) ─────────────────────────
  // Obtiene items del carrito y enriquece con datos del microservicio articulos
  @GET @Path("consulta_carrito_compra") @Produces(MediaType.APPLICATION_JSON)
  public Response consulta_carrito_compra(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    if (!verificaAccesoRemoto(id_usuario, token)) return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "SELECT id_articulo,cantidad FROM carrito_compra WHERE id_usuario=?");
      s.setInt(1, id_usuario);
      ResultSet rs = s.executeQuery();

      ArrayList<Map<String, Object>> carrito = new ArrayList<>();
      while (rs.next()) {
        int id_art = rs.getInt("id_articulo");
        int cant   = rs.getInt("cantidad");

        // Consultar datos del artículo al microservicio articulos
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ARTICULOS_URL + "/consulta_articulo_por_id?id_articulo=" + id_art))
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) continue; // artículo no encontrado, omitir

        @SuppressWarnings("unchecked")
        Map<String, Object> art = j.readValue(resp.body(), Map.class);

        double precio = ((Number) art.get("precio")).doubleValue();
        double costo  = precio * cant;

        Map<String, Object> item = new HashMap<>();
        item.put("id_articulo", id_art);
        item.put("cantidad",    cant);
        item.put("nombre",      art.get("nombre"));
        item.put("precio",      precio);
        item.put("costo",       costo);
        item.put("fotografia",  art.get("fotografia"));
        carrito.add(item);
      }

      return Response.ok(j.writeValueAsString(carrito)).build();
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────────
  private boolean verificaAccesoRemoto(int id_usuario, String token) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(USUARIOS_URL + "/verifica_acceso?id_usuario=" + id_usuario +
            "&token=" + java.net.URLEncoder.encode(token, "UTF-8")))
        .GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) return false;
    return resp.body().contains("\"acceso\":true");
  }

  private Response bad(String msg) throws Exception {
    return Response.status(400)
        .entity(j.writeValueAsString(new Respuesta(msg))).build();
  }
}
