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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

@Path("ws")
public class Servicio extends Application {

  static DataSource pool = null;
  static {
    try {
      Context ctx = new InitialContext();
      pool = (DataSource) ctx.lookup("java:comp/env/jdbc/datasource_Articulos");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static final ObjectMapper j =
      new ObjectMapper().setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"));

  static final HttpClient http = HttpClient.newHttpClient();

  // URL base del servicio de usuarios y compras (inyectadas por env o default ClusterIP)
  static final String USUARIOS_URL = System.getenv().getOrDefault(
      "USUARIOS_URL", "http://usuarios-svc:8080/Usuarios/rest/ws");
  static final String COMPRAS_URL = System.getenv().getOrDefault(
      "COMPRAS_URL", "http://compras-svc:8080/Compras/rest/ws");

  // ── RF-BE-2.1 articulos/alta_articulo ────────────────────────────────────────
  @POST @Path("alta_articulo")
  @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
  public Response alta_articulo(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token,
      Articulo articulo) throws Exception {

    // Validar campos
    if (articulo.nombre      == null || articulo.nombre.isEmpty())
      return bad("Se debe ingresar el nombre del artículo");
    if (articulo.descripcion == null || articulo.descripcion.isEmpty())
      return bad("Se debe ingresar la descripción del artículo");
    if (articulo.precio <= 0)
      return bad("El precio debe ser mayor a cero");
    if (articulo.cantidad < 0)
      return bad("La cantidad no puede ser negativa");
    if (articulo.fotografia == null)
      return bad("Se debe ingresar la fotografía del artículo");

    // Verificar acceso vía microservicio usuarios
    if (!verificaAccesoRemoto(id_usuario, token))
      return bad("Acceso denegado");

    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      int id_articulo = 0;
      try {
        // Insertar en stock de articulos (sin cantidad)
        PreparedStatement s1 = c.prepareStatement(
            "INSERT INTO stock(nombre,descripcion,precio) VALUES(?,?,?)",
            Statement.RETURN_GENERATED_KEYS);
        s1.setString(1, articulo.nombre);
        s1.setString(2, articulo.descripcion);
        s1.setDouble(3, articulo.precio);
        s1.executeUpdate();

        ResultSet keys = s1.getGeneratedKeys();
        if (!keys.next()) throw new Exception("No se pudo obtener el ID del artículo");
        id_articulo = keys.getInt(1);

        // Insertar foto
        PreparedStatement s2 = c.prepareStatement(
            "INSERT INTO fotos_articulos(fotografia,id_articulo) VALUES(?,?)");
        s2.setBytes(1, articulo.fotografia);
        s2.setInt(2, id_articulo);
        s2.executeUpdate();

        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }

      // Llamar a compras/alta_articulo para registrar la cantidad en la BD de compras
      String body = "{\"id_articulo\":" + id_articulo +
          ",\"cantidad\":" + articulo.cantidad +
          ",\"id_usuario\":" + id_usuario +
          ",\"token\":\"" + token + "\"}";

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(COMPRAS_URL + "/alta_articulo"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        // Si compras falla, revertir la inserción en articulos
        try (Connection c2 = pool.getConnection()) {
          c2.prepareStatement("DELETE FROM stock WHERE id_articulo=" + id_articulo).executeUpdate();
        }
        return bad("Error al registrar cantidad en compras: " + resp.body());
      }
    }

    return Response.ok(j.writeValueAsString(new Respuesta("OK"))).build();
  }

  // ── RF-BE-2.2 articulos/consulta_articulos ────────────────────────────────────
  @GET @Path("consulta_articulos") @Produces(MediaType.APPLICATION_JSON)
  public Response consulta_articulos(
      @QueryParam("palabra") String palabra,
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    if (!verificaAccesoRemoto(id_usuario, token)) return bad("Acceso denegado");

    if (palabra == null) palabra = "";
    String like = "%" + palabra + "%";

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "SELECT s.id_articulo, f.fotografia, s.nombre, s.descripcion, s.precio " +
          "FROM stock s LEFT JOIN fotos_articulos f ON s.id_articulo=f.id_articulo " +
          "WHERE s.nombre LIKE ? OR s.descripcion LIKE ?");
      s.setString(1, like);
      s.setString(2, like);
      ResultSet rs = s.executeQuery();

      ArrayList<Map<String, Object>> lista = new ArrayList<>();
      while (rs.next()) {
        Map<String, Object> a = new HashMap<>();
        a.put("id_articulo", rs.getInt("id_articulo"));
        byte[] foto = rs.getBytes("fotografia");
        a.put("fotografia", foto != null ? Base64.getEncoder().encodeToString(foto) : null);
        a.put("nombre",      rs.getString("nombre"));
        a.put("descripcion", rs.getString("descripcion"));
        a.put("precio",      rs.getBigDecimal("precio"));
        lista.add(a);
      }
      return Response.ok(j.writeValueAsString(lista)).build();
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── consulta_articulo_por_id (uso interno desde compras) ─────────────────────
  // Sin autenticación: solo accesible por ClusterIP interno
  @GET @Path("consulta_articulo_por_id") @Produces(MediaType.APPLICATION_JSON)
  public Response consulta_articulo_por_id(
      @QueryParam("id_articulo") int id_articulo) throws Exception {

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "SELECT s.id_articulo, f.fotografia, s.nombre, s.descripcion, s.precio " +
          "FROM stock s LEFT JOIN fotos_articulos f ON s.id_articulo=f.id_articulo " +
          "WHERE s.id_articulo=?");
      s.setInt(1, id_articulo);
      ResultSet rs = s.executeQuery();
      if (!rs.next()) return bad("Artículo no encontrado");

      Map<String, Object> a = new HashMap<>();
      a.put("id_articulo", rs.getInt("id_articulo"));
      byte[] foto = rs.getBytes("fotografia");
      a.put("fotografia", foto != null ? Base64.getEncoder().encodeToString(foto) : null);
      a.put("nombre",      rs.getString("nombre"));
      a.put("descripcion", rs.getString("descripcion"));
      a.put("precio",      rs.getBigDecimal("precio"));
      return Response.ok(j.writeValueAsString(a)).build();
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
