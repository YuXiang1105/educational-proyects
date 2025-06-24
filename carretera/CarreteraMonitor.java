// Nunca cambia la declaracion del package!
package cc.carretera;

import es.upm.babel.cclib.Monitor;
import java.util.Iterator;
import es.upm.aedlib.map.*;
import es.upm.aedlib.Entry;

/**
 * Implementación del recurso compartido Carretera con Monitores
 */
public class CarreteraMonitor implements Carretera {

  private Monitor mutex; 
  private Monitor.Cond[] entrada;      // Condiciones de los monitores para que entre cuando hay espacio
  private Monitor.Cond[][] circulando; // condiciones para los coches que se mantienen circulando en cada segmento, indicados en segmento, carril

  private boolean[][] posicionesLibre;     // indica que el segmento i, carril j está ocupado, true si está ocupado
  private Pos[][] posiciones;              // todas las clases Pos
  private Map<String, Pos> cochesPosicion; // Map con Id como Key y Pos como Dato, si está en carretera.
  private Map<String, Integer> tiempos;    // Map con Id como Key y tks como Dato, si está en carretera.

  public CarreteraMonitor(int segmentos, int carriles) {
    //Tenemos en cuenta lo siguiente, todas las posiciones de array, condiciones, y arrays multidimensionales, 
    //con indices [i][j], i o j = 0, en ese dato se guarda null, esto para mantener la consistencia con los test
    this.posicionesLibre = new boolean[segmentos + 1 ][carriles + 1]; 
    this.cochesPosicion = new HashTableMap<String, Pos>();
    this.tiempos = new HashTableMap<String, Integer>();
    this.mutex = new Monitor();
    this.entrada = new Monitor.Cond[segmentos + 1];
    this.posiciones = new Pos[segmentos + 1][carriles + 1];
    this.circulando = new Monitor.Cond[segmentos + 1][carriles + 1];

    for (int i = 1; i < entrada.length; i++) {
      this.entrada[i] = mutex.newCond();
      for (int j = 1; j < posiciones[i].length; j++) {
        this.posiciones[i][j] = new Pos(i,j); 
        this.circulando[i][j] = mutex.newCond();
        this.posicionesLibre[i][j] = true;
      }
    }
  }

  public Pos entrar(String id, int tks) {
    mutex.enter();
    desbloqueoGenerico();
    // Buscamos si hay algun carril libre. En caso de que no haya, se bloquea hasta que haya espacio
    int i = this.carrilDisponible(1);
    if (i == this.posicionesLibre[1].length) {
      entrada[1].await(); 
      i = this.carrilDisponible(1);
    }
    //Actualizamos todos los arrays para mantener la consistencia en en array de booleans, maps, etc.
    Pos res = this.posiciones[1][i];
    this.posicionesLibre[1][i] = false;
    this.cochesPosicion.put(id, res);
    this.tiempos.put(id, tks);
    desbloqueoGenerico(); //Volvemos a comprobar las Cpre
    mutex.leave();
    return res;
  }

  public Pos avanzar(String id, int tks) {
    mutex.enter();
    int carrilActual = this.cochesPosicion.get(id).getCarril();
    int segmentoActual = this.cochesPosicion.get(id).getSegmento();
    //Carril disponible busca si hay espacio en el segmento, esto sale del array de booleans
    int i = this.carrilDisponible(segmentoActual + 1);
    if (i == this.posicionesLibre[1].length) {
      entrada[segmentoActual + 1].await(); //en caso de no haber se bloquea hasta que cumpla la CPre
      //Buscamos, de nuevo, como sabemos que se la liberado uno de ese carril no hace falta poner otro if, ya que el desbloqueo generico asegura la CPre
      i = this.carrilDisponible(segmentoActual + 1);
    }

    Pos res = posiciones[segmentoActual + 1][i];
    tiempos.put(id, tks); 
    cochesPosicion.put(id, res);
    //actualizamos el array de booleans poniendo el viejo a libre de nuevo
    this.posicionesLibre[segmentoActual][carrilActual] = true;
    this.posicionesLibre[segmentoActual + 1][i] = false;
    desbloqueoGenerico();//Comprobamos de nuevo si algun coche cumple las CPre
    mutex.leave();
    return res;
  }

  public void circulando(String id) {
    this.mutex.enter();
    int segmentoActual = this.cochesPosicion.get(id).getSegmento();
    int carrilActual = this.cochesPosicion.get(id).getCarril();
    //Comprobamos la CPre para que cumpla la Cpre al desbloquear en cadena por el desbloqueador generico
    if(this.tiempos.get(id) != 0){ 
    this.circulando[segmentoActual][carrilActual].await();
    }
    desbloqueoGenerico();
    this.mutex.leave();
  }

  public void salir(String id) {
    this.mutex.enter();
    //quitamos todos los datos del coche de la clase
    int segmentoActual = this.cochesPosicion.get(id).getSegmento();
    int carrilActual = this.cochesPosicion.get(id).getCarril(); 
    this.tiempos.remove(id);
    this.cochesPosicion.remove(id);
    //ponemos esa posicion a true sin poner otra a false ya que esta solo saliendo
    this.posicionesLibre[segmentoActual][carrilActual] = true;
    desbloqueoGenerico();
    this.mutex.leave();
  }

  public void tick() {
    this.mutex.enter();
    for (String key : tiempos.keys()) { //decrementa todos los tick en el map de tiempos
      int valor = this.tiempos.get(key);
      if (valor > 0) {
        Integer tiempoNuevo = this.tiempos.get(key) -1 ;
        this.tiempos.put(key, tiempoNuevo);
      }
    }
    desbloqueoGenerico();//posteriormente comprobamos si algun coche ha llegado a tick 0
    this.mutex.leave();
  }

  //Función que hace signal en cadena para los que cumplan las CPre
  private void desbloqueoGenerico() {
    boolean estaRecorriendo = true; //Boolean para hacer solo un signal
    Iterator<Entry<String, Pos>> cocheIterable = this.cochesPosicion.entries().iterator();

    while (estaRecorriendo && cocheIterable.hasNext()) { //Recorremos todos los coches que han entrado
      Entry<String, Pos> coche = cocheIterable.next(); 
      Pos cochePos = coche.getValue();
      Integer segmento = cochePos.getSegmento();
      Integer carril = cochePos.getCarril();
      Integer tiempo = this.tiempos.get(coche.getKey());

      if(this.circulando[segmento][carril].waiting() > 0 && tiempo == 0){ //CPre
        this.circulando[segmento][carril].signal(); //Hacemos signal al coche que compla las CPre y que haya entrado previamente
        estaRecorriendo = false;
      }
    }
    for (int i = 1; i < this.circulando.length && estaRecorriendo; i++) { //Se recorren todos los segmentos
      Integer hayCoche = this.carrilDisponible(i);
      if (this.entrada[i].waiting() > 0 && hayCoche != this.posicionesLibre[1].length) { //CPre
        estaRecorriendo = false;
        this.entrada[i].signal();
      }
    }
  }

  //Devuelve el primer carril libre del segmento en el array de booleans. Si no hay carril libre devuelve el tamaño del array
  private int carrilDisponible(Integer segmento) {
    int i = 1;
    for (; i < this.posicionesLibre[1].length && !this.posicionesLibre[segmento][i]; i++);
    return i;
  }
}