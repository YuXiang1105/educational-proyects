package cc.carretera;

import es.upm.aedlib.map.*;
import java.util.LinkedList;
import java.util.Queue;
import org.jcsp.lang.*;

public class CarreteraCSP implements Carretera, CSProcess {

  // Ej. private Any2One chOp;
  private Any2OneChannel entrarCarretera;
  private Any2OneChannel mantenerTick;
  private Any2OneChannel huecoDisp;
  private One2OneChannel ticks;
  private Any2OneChannel salirCarretera;

  // Configuración de la carretera
  private Pos[][] posiciones; // todas las clases Pos
  private int segmentos;
  private int carriles;

  public CarreteraCSP(int segmentos, int carriles) {
    this.segmentos = segmentos;
    this.carriles = carriles;
    // Creación de canales para comunicación con el servidor
    entrarCarretera = Channel.any2one();
    mantenerTick = Channel.any2one();
    huecoDisp = Channel.any2one();
    ticks = Channel.one2one();
    salirCarretera = Channel.any2one();

    this.posiciones = new Pos[segmentos + 1][carriles + 1];
    for (int i = 1; i < posiciones.length; i++) {
      for (int j = 1; j < posiciones[i].length; j++) {
        this.posiciones[i][j] = new Pos(i, j);
      }
    }
    // Puesta en marcha del servidor: alternativa sucia (desde el
    // punto de vista de CSP) a Parallel que nos ofrece JCSP para
    // poner en marcha un CSProcess
    new ProcessManager(this).start();
  }

  public Pos entrar(String car, int tks) {
    One2OneChannel ACK = Channel.one2one();
    Object[] enviar = { car, tks, ACK };
    this.entrarCarretera.out().write(enviar);
    Pos res = (Pos) ACK.in().read();
    return res;
  }

  public Pos avanzar(String car, int tks) {
    One2OneChannel ACK = Channel.one2one();
    Object[] enviar = { car, tks, ACK };
    this.huecoDisp.out().write(enviar);
    Pos res = (Pos) ACK.in().read();
    return res;
  }

  public void salir(String car) {// Siempre puede salir, luego se libera solo por tick, no hace mas
    One2OneChannel ACK = Channel.one2one();
    Object[] enviar = { car, ACK };
    this.salirCarretera.out().write(enviar);
    ACK.in().read(); // nos aseguramos de reescribir todos los datos antes de liberar salir con este
                     // read()
  }

  public void circulando(String car) {
    One2OneChannel ACK = Channel.one2one();
    Object[] enviar = { car, ACK };
    this.mantenerTick.out().write(enviar);
    Pos res = (Pos) ACK.in().read();
  }

  public void tick() {
    this.ticks.out().write(null);
  } // tick no bloquea, solo circular

  // Código del servidor
  public void run() {
    final int ENTRARCARRETERA = 0;
    final int LIBERARTICK0 = 1;
    final int HAYHUECOAVANZAR = 2;
    final int DECREMENTARTICKS = 3;
    final int SALIR = 4;
    // declaración e inicialización del estado del recurso
    final boolean[][] posicionesLibre = new boolean[segmentos + 1][carriles + 1];
    final Map<String, Pos> cochesPosicion = new HashTableMap<String, Pos>();// Map con Id como Key y Pos como Dato, si
                                                                            // está en carretera.
    final Map<String, Integer> tiempos = new HashTableMap<String, Integer>();
    // se usaran el el servidor los siguientes objetos/variables
    Object[] datos;
    String nombreCoche;
    Integer tsk_a_Poner;
    One2OneChannel ACK;
    Integer carrilNuevo;
    for (int i = 1; i < posicionesLibre.length; i++) {
      for (int j = 1; j < posiciones[i].length; j++) {
        posicionesLibre[i][j] = true;
      }
    }

    // declaración e inicialización de estructuras de datos para
    // almacenar peticiones de los clientes
    Queue<Object[]> bufferAtrTick = new LinkedList<>();
    Queue<Object[]> bufferAtrrCarrilSinHueco = new LinkedList<>();
    Queue<Object[]> bufferAtrrEntrada = new LinkedList<>();
    // declaración e inicialización de arrays necesarios para
    // poder hacer la recepción no determinista (Alternative)
    AltingChannelInput canalEntrar = entrarCarretera.in();
    AltingChannelInput canalInTick = mantenerTick.in();
    AltingChannelInput canalInSegDisp = huecoDisp.in();
    AltingChannelInput canalTick = ticks.in();
    AltingChannelInput canalSalir = salirCarretera.in();
    Guard[] alter = { canalEntrar, canalInTick, canalInSegDisp, canalTick, canalSalir };

    // cambiar null por el array de canales
    Alternative servicios = new Alternative(alter);

    // Bucle principal del servicio
    while (true) {
      int servicio;
      // cálculo de las guardas
      boolean[] condiciones = { true, true, true, true, true}; // Hacemos ambos por peticiones aplazadas, demasiada variabilidad de
      // segmentos y carriles
      // los dos ultimos corresponden a tick y salir, siempre se puede decrementar
      // tick y salir, no hay pre, luego es true
      servicio = servicios.fairSelect(condiciones);
      // ejecutar la operación solicitada por el cliente
      switch (servicio) {
        case ENTRARCARRETERA:
          carrilNuevo = carrilDisponible(1, posicionesLibre);
          datos = (Object[]) canalEntrar.read();
          tsk_a_Poner = (Integer) datos[1];
          nombreCoche = (String) datos[0];
          if (carrilNuevo != posicionesLibre[1].length) {
            posicionesLibre[1][carrilNuevo] = false;
            tiempos.put(nombreCoche, tsk_a_Poner);
            cochesPosicion.put(nombreCoche, posiciones[1][carrilNuevo]);
            ACK = (One2OneChannel) datos[2];
            ACK.out().write(posiciones[1][carrilNuevo]);
          } else {
            bufferAtrrEntrada.add(datos);
          }
          break;

        case LIBERARTICK0:
          datos = (Object[]) canalInTick.read(); // 2 datos en el array, el primero el coche, el segundo el ACK
          bufferAtrTick.add(datos); // se debe liberar el ACK tras el while
          // Sabemos que circulando no envia coches con tiempo inicial 0, luego la CPre
          // nunca se cumple
          // al recibir el mensaje, siempre se aplaza el ACK
          break;

        case HAYHUECOAVANZAR:
          datos = (Object[]) canalInSegDisp.read();
          nombreCoche = (String) datos[0];
          tsk_a_Poner = (Integer) datos[1];
          // suponemos inicialmente que ya ha entrado
          Integer segmentoActual = cochesPosicion.get(nombreCoche).getSegmento();
          Integer carrilActual = cochesPosicion.get(nombreCoche).getCarril();
          // Buscamos el carril del segmento a entrar
          carrilNuevo = carrilDisponible(segmentoActual + 1, posicionesLibre);
          // Caso del que haya una posicion libre
          if (carrilNuevo != posicionesLibre[1].length) {
            cochesPosicion.put(nombreCoche, posiciones[segmentoActual + 1][carrilNuevo]);
            tiempos.put(nombreCoche, tsk_a_Poner);
            if (segmentoActual != 0) {
              posicionesLibre[segmentoActual][carrilActual] = true;
            }
            posicionesLibre[segmentoActual + 1][carrilNuevo] = false;
            ACK = (One2OneChannel) datos[2];
            ACK.out().write(posiciones[segmentoActual + 1][carrilNuevo]);
          } else { // Caso de que no haya posicion libre
            // caso el coche no tiene hueco, da igual si es entrar() o avanzar()
            bufferAtrrCarrilSinHueco.add(datos);
          }
          break;

        case DECREMENTARTICKS:
          for (String key : tiempos.keys()) { // decrementa todos los tick en el map de tiempos
            int valor = tiempos.get(key);
            if (valor > 0) {
              Integer tiempoNuevo = tiempos.get(key) - 1;
              tiempos.put(key, tiempoNuevo);
            }
          }
          this.ticks.in().read();
          break;
        case SALIR:
          datos = (Object[]) canalSalir.read();
          nombreCoche = (String) datos[0];
          Pos posicionCoche = cochesPosicion.get(nombreCoche);
          posicionesLibre[posicionCoche.getSegmento()][posicionCoche.getCarril()] = true;
          cochesPosicion.remove(nombreCoche);
          tiempos.remove(nombreCoche);
          ACK = (One2OneChannel) datos[1];
          ACK.out().write(null);
          break;
      }
      // Pendientes avanzar, entra al buffer si no hay carril disponible delante de
      // ese coche
      int longitudPendientes = bufferAtrrCarrilSinHueco.size();
      for (int i = 0; i < longitudPendientes; i++) {
        datos = (Object[]) bufferAtrrCarrilSinHueco.poll();
        nombreCoche = (String) datos[0];
        tsk_a_Poner = (Integer) datos[1];
        // suponemos inicialmente que ya ha entrado
        Integer segmentoActual = cochesPosicion.get(nombreCoche).getSegmento();
        Integer carrilActual = cochesPosicion.get(nombreCoche).getCarril();
        // Buscamos el carril del segmento a entrar
        carrilNuevo = carrilDisponible(segmentoActual + 1, posicionesLibre);
        // Caso del que haya una posicion libre
        if (carrilNuevo != posicionesLibre[1].length) {
          cochesPosicion.put(nombreCoche, posiciones[segmentoActual + 1][carrilNuevo]);
          tiempos.put(nombreCoche, tsk_a_Poner);
          posicionesLibre[segmentoActual][carrilActual] = true;
          posicionesLibre[segmentoActual + 1][carrilNuevo] = false;
          ACK = (One2OneChannel) datos[2];
          ACK.out().write(posiciones[segmentoActual + 1][carrilNuevo]);
        } else { // Caso de que no haya posicion libre
          // caso el coche no tiene hueco, da igual si es entrar() o avanzar()
          bufferAtrrCarrilSinHueco.add(datos);
        }
      }
      // bloqueos por intentar entrar pero no hay huecos disponibles
      longitudPendientes = bufferAtrrEntrada.size();
      for (int i = 0; i < longitudPendientes; i++) {
        carrilNuevo = carrilDisponible(1, posicionesLibre);
        datos = (Object[]) bufferAtrrEntrada.poll();
        if (carrilNuevo != posicionesLibre[1].length) {
          tsk_a_Poner = (Integer) datos[1];
          nombreCoche = (String) datos[0];

          posicionesLibre[1][carrilNuevo] = false;
          cochesPosicion.put(nombreCoche, posiciones[1][carrilNuevo]);
          tiempos.put(nombreCoche, tsk_a_Poner);
          ACK = (One2OneChannel) datos[2];
          ACK.out().write(posiciones[1][carrilNuevo]);
        } else {
          bufferAtrrEntrada.add(datos);
        }
      }

      // bloqueos por tick de coche
      longitudPendientes = bufferAtrTick.size();
      for (int j = 0; j < longitudPendientes; j++) {
        datos = bufferAtrTick.poll();
        nombreCoche = (String) datos[0];
        if (tiempos.get(nombreCoche) == 0) {
          ACK = (One2OneChannel) datos[1];
          ACK.out().write(null);
        } else {
          bufferAtrTick.add(datos);
        }
      }
    }
  }

  // Devuelve el primer carril libre del segmento en el array de booleans. Si no
  // hay carril libre devuelve el tamaño del array
  private int carrilDisponible(Integer segmento, boolean[][] posicionesLibre) {
    int i = 1;
    for (; i < posicionesLibre[1].length && !posicionesLibre[segmento][i]; i++)
      ;
    return i;
  }

}