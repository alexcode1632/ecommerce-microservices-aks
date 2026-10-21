package servicio;

public class Articulo {
  public String nombre;
  public String descripcion;
  public double precio;
  public int    cantidad;   // solo usado en alta; no persiste en esta DB
  public byte[] fotografia;
}
