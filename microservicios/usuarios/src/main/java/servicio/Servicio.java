package servicio;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Application;

import java.sql.*;
import javax.sql.DataSource;
import javax.naming.Context;
import javax.naming.InitialContext;

import java.text.SimpleDateFormat;
import java.security.SecureRandom;

import com.fasterxml.jackson.databind.ObjectMapper;

@Path("ws")
public class Servicio extends Application {

  static DataSource pool = null;
  static {
    try {
      Context ctx = new InitialContext();
      pool = (DataSource) ctx.lookup("java:comp/env/jdbc/datasource_Usuarios");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  static final ObjectMapper j =
      new ObjectMapper().setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"));

  static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  static final SecureRandom RNG = new SecureRandom();

  static String generarToken(int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
    return sb.toString();
  }

  // ── RF-BE-1.1 login ──────────────────────────────────────────────────────────
  @GET @Path("login") @Produces(MediaType.APPLICATION_JSON)
  public Response login(
      @QueryParam("email") String email,
      @QueryParam("password") String password) throws Exception {

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "SELECT id_usuario FROM usuarios WHERE email=? AND password=?");
      s.setString(1, email);
      s.setString(2, password);
      ResultSet rs = s.executeQuery();
      if (rs.next()) {
        int id = rs.getInt(1);
        String tok = generarToken(20);
        PreparedStatement u = c.prepareStatement(
            "UPDATE usuarios SET token=? WHERE id_usuario=?");
        u.setString(1, tok);
        u.setInt(2, id);
        u.executeUpdate();
        return Response.ok("{\"id_usuario\":" + id + ",\"token\":\"" + tok + "\"}").build();
      }
      return Response.status(400)
          .entity(j.writeValueAsString(new Respuesta("Acceso denegado"))).build();
    } catch (Exception e) {
      return Response.status(400)
          .entity(j.writeValueAsString(new Respuesta(e.getMessage()))).build();
    }
  }

  // ── RF-BE-1.2 verifica_acceso ─────────────────────────────────────────────────
  @GET @Path("verifica_acceso") @Produces(MediaType.APPLICATION_JSON)
  public Response verifica_acceso(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    try (Connection c = pool.getConnection()) {
      PreparedStatement s = c.prepareStatement(
          "SELECT 1 FROM usuarios WHERE id_usuario=? AND token=?");
      s.setInt(1, id_usuario);
      s.setString(2, token);
      ResultSet rs = s.executeQuery();
      boolean ok = rs.next();
      return Response.ok("{\"acceso\":" + ok + "}").build();
    } catch (Exception e) {
      return Response.status(400)
          .entity(j.writeValueAsString(new Respuesta(e.getMessage()))).build();
    }
  }

  // ── RF-BE-1.3 alta_usuario ────────────────────────────────────────────────────
  @POST @Path("alta_usuario")
  @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
  public Response alta_usuario(Usuario u) throws Exception {

    if (u.email            == null || u.email.isEmpty())
      return bad("Se debe ingresar el email");
    if (u.password         == null || u.password.isEmpty())
      return bad("Se debe ingresar la contraseña");
    if (u.nombre           == null || u.nombre.isEmpty())
      return bad("Se debe ingresar el nombre");
    if (u.apellido_paterno == null || u.apellido_paterno.isEmpty())
      return bad("Se debe ingresar el apellido paterno");
    if (u.fecha_nacimiento == null)
      return bad("Se debe ingresar la fecha de nacimiento");

    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        PreparedStatement s1 = c.prepareStatement(
            "INSERT INTO usuarios(id_usuario,email,password,nombre,apellido_paterno," +
            "apellido_materno,fecha_nacimiento,telefono,genero) VALUES(0,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS);
        s1.setString(1, u.email);
        s1.setString(2, u.password);
        s1.setString(3, u.nombre);
        s1.setString(4, u.apellido_paterno);
        if (u.apellido_materno != null) s1.setString(5, u.apellido_materno);
        else s1.setNull(5, Types.VARCHAR);
        s1.setTimestamp(6, u.fecha_nacimiento);
        if (u.telefono != null) s1.setLong(7, u.telefono);
        else s1.setNull(7, Types.BIGINT);
        if (u.genero != null) s1.setString(8, u.genero);
        else s1.setNull(8, Types.CHAR);
        s1.executeUpdate();

        ResultSet keys = s1.getGeneratedKeys();
        if (!keys.next()) throw new Exception("No se pudo obtener el ID del usuario");
        int id = keys.getInt(1);

        if (u.foto != null) {
          PreparedStatement s2 = c.prepareStatement(
              "INSERT INTO fotos_usuarios(id_foto,foto,id_usuario) VALUES(0,?,?)");
          s2.setBytes(1, u.foto);
          s2.setInt(2, id);
          s2.executeUpdate();
        }
        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("Se dio de alta el usuario"))).build();
      } catch (Exception e) {
        c.rollback();
        return bad(e.getMessage());
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  // ── RF-BE-1.4 consulta_usuario ────────────────────────────────────────────────
  @GET @Path("consulta_usuario") @Produces(MediaType.APPLICATION_JSON)
  public Response consulta_usuario(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    try (Connection c = pool.getConnection()) {
      if (!checkAcceso(c, id_usuario, token))
        return bad("Acceso denegado");

      PreparedStatement s = c.prepareStatement(
          "SELECT a.email,a.nombre,a.apellido_paterno,a.apellido_materno," +
          "a.fecha_nacimiento,a.telefono,a.genero,b.foto " +
          "FROM usuarios a LEFT JOIN fotos_usuarios b ON a.id_usuario=b.id_usuario " +
          "WHERE a.id_usuario=?");
      s.setInt(1, id_usuario);
      ResultSet rs = s.executeQuery();
      if (rs.next()) {
        Usuario r = new Usuario();
        r.email            = rs.getString(1);
        r.nombre           = rs.getString(2);
        r.apellido_paterno = rs.getString(3);
        r.apellido_materno = rs.getString(4);
        r.fecha_nacimiento = rs.getTimestamp(5);
        r.telefono         = rs.getObject(6) != null ? rs.getLong(6) : null;
        r.genero           = rs.getString(7);
        r.foto             = rs.getBytes(8);
        return Response.ok(j.writeValueAsString(r)).build();
      }
      return bad("Usuario no encontrado");
    } catch (Exception e) {
      return bad(e.getMessage());
    }
  }

  // ── RF-BE-1.5 modifica_usuario ────────────────────────────────────────────────
  @PUT @Path("modifica_usuario")
  @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
  public Response modifica_usuario(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token,
      Usuario u) throws Exception {

    try (Connection c = pool.getConnection()) {
      if (!checkAcceso(c, id_usuario, token)) return bad("Acceso denegado");
      if (u.email            == null || u.email.isEmpty())  return bad("Se debe ingresar el email");
      if (u.nombre           == null || u.nombre.isEmpty()) return bad("Se debe ingresar el nombre");
      if (u.apellido_paterno == null || u.apellido_paterno.isEmpty()) return bad("Se debe ingresar el apellido paterno");
      if (u.fecha_nacimiento == null) return bad("Se debe ingresar la fecha de nacimiento");

      c.setAutoCommit(false);
      try {
        PreparedStatement s1 = c.prepareStatement(
            "UPDATE usuarios SET email=?,nombre=?,apellido_paterno=?," +
            "apellido_materno=?,fecha_nacimiento=?,telefono=?,genero=? WHERE id_usuario=?");
        s1.setString(1, u.email);
        s1.setString(2, u.nombre);
        s1.setString(3, u.apellido_paterno);
        if (u.apellido_materno != null) s1.setString(4, u.apellido_materno);
        else s1.setNull(4, Types.VARCHAR);
        s1.setTimestamp(5, u.fecha_nacimiento);
        if (u.telefono != null) s1.setLong(6, u.telefono);
        else s1.setNull(6, Types.BIGINT);
        if (u.genero != null) s1.setString(7, u.genero);
        else s1.setNull(7, Types.CHAR);
        s1.setInt(8, id_usuario);
        s1.executeUpdate();

        if (u.password != null && !u.password.isEmpty()) {
          PreparedStatement s2 = c.prepareStatement(
              "UPDATE usuarios SET password=? WHERE id_usuario=?");
          s2.setString(1, u.password);
          s2.setInt(2, id_usuario);
          s2.executeUpdate();
        }

        PreparedStatement s3 = c.prepareStatement(
            "DELETE FROM fotos_usuarios WHERE id_usuario=?");
        s3.setInt(1, id_usuario);
        s3.executeUpdate();

        if (u.foto != null) {
          PreparedStatement s4 = c.prepareStatement(
              "INSERT INTO fotos_usuarios(id_foto,foto,id_usuario) VALUES(0,?,?)");
          s4.setBytes(1, u.foto);
          s4.setInt(2, id_usuario);
          s4.executeUpdate();
        }
        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("Se modificó el usuario"))).build();
      } catch (Exception e) {
        c.rollback();
        return bad(e.getMessage());
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  // ── RF-BE-1.6 borra_usuario ───────────────────────────────────────────────────
  @DELETE @Path("borra_usuario") @Produces(MediaType.APPLICATION_JSON)
  public Response borra_usuario(
      @QueryParam("id_usuario") int id_usuario,
      @QueryParam("token") String token) throws Exception {

    try (Connection c = pool.getConnection()) {
      if (!checkAcceso(c, id_usuario, token)) return bad("Acceso denegado");

      c.setAutoCommit(false);
      try {
        PreparedStatement s1 = c.prepareStatement(
            "DELETE FROM fotos_usuarios WHERE id_usuario=?");
        s1.setInt(1, id_usuario);
        s1.executeUpdate();

        PreparedStatement s2 = c.prepareStatement(
            "DELETE FROM usuarios WHERE id_usuario=?");
        s2.setInt(1, id_usuario);
        s2.executeUpdate();

        c.commit();
        return Response.ok(j.writeValueAsString(new Respuesta("Se borró el usuario"))).build();
      } catch (Exception e) {
        c.rollback();
        return bad(e.getMessage());
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────────
  private boolean checkAcceso(Connection c, int id_usuario, String token) throws Exception {
    PreparedStatement s = c.prepareStatement(
        "SELECT 1 FROM usuarios WHERE id_usuario=? AND token=?");
    s.setInt(1, id_usuario);
    s.setString(2, token);
    return s.executeQuery().next();
  }

  private Response bad(String msg) throws Exception {
    return Response.status(400)
        .entity(j.writeValueAsString(new Respuesta(msg))).build();
  }
}
