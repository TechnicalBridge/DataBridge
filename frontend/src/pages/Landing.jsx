import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import BarraEstado from "../components/BarraEstado";
import LogoPasarela from "../components/LogoPasarela";
import Puente from "../components/Puente";
import { IconoCheck, IconoFlecha } from "../components/Iconos";

/**
 * Un ejemplo de lo que el deudor ve adentro: un arriendo en 6 cuotas, con la
 * primera pagada. Generico a proposito: no es una deuda ni una empresa de la demo.
 */
const EJEMPLO = { estado: "repacted", cuotasPagadas: 1, cuotasTotales: 6, conConvenio: true };
/** La misma deuda al final del cruce: las seis cuotas pagadas. */
const EJEMPLO_PAGADA = { estado: "paid", cuotasPagadas: 6, cuotasTotales: 6, conConvenio: true };

/** Los tablones del puente: lo que pasa desde que llega el codigo hasta pagar (#536). */
const PASOS = [
  ["Te llega un código", "Al correo que tiene registrado la empresa a la que le debes. Sirve una sola vez."],
  ["Entras con tu RUT", "Sin crear una cuenta ni recordar una contraseña: tu RUT y el código."],
  ["Ves lo que debes", "Cada deuda con su monto, a quién se la debes y los meses que incluye."],
  ["Pagas como te acomode", "Todo de una vez, o en un convenio de 3 a 24 cuotas que aceptas ahí mismo."],
];

/** Lo mismo, corto, para los tablones del puente en la primera pantalla. */
const TABLONES = [
  ["Te llega un código", "Solo eso: sin monto ni enlace"],
  ["Entras con tu RUT", "Sin cuenta ni contraseña"],
  ["Ves lo que debes", "Monto, acreedor y meses"],
  ["Pagas como te acomode", "Todo o de 3 a 24 cuotas"],
];

/** Por que es seguro pagar aqui: cada punto es algo que el sistema hace hoy. */
const GARANTIAS = [
  ["El monto lo calcula DataBridge", "Sale de la deuda registrada por la empresa. Nadie lo escribe a mano en el camino."],
  ["El pago se abona cuando la pasarela lo confirma", "Y de cada pago queda un comprobante. Al terminar, descargas el certificado de deuda pagada."],
  ["Si no reconoces una deuda, la reclamas", "Desde el mismo portal. Mientras la empresa la revisa, no se te cobra."],
];

const PARA_EMPRESAS = [
  "Cargas a tus deudores en una campaña, desde un Excel, y antes de guardar ves cuáles entran y cuáles no, con su motivo.",
  "Entran solo deudas en mora: las que llevan 30 días o más desde su cargo impago más antiguo.",
  "DataBridge le escribe al deudor según tu campaña y dentro de lo que permite la ley: hasta dos correos por semana, de lunes a sábado entre 8:00 y 20:00, nunca en feriados (art. 37 de la Ley 19.496).",
  "Ves en qué va cada deuda y cada pago confirmado, y lo descargas en una planilla.",
];

const PREGUNTAS = [
  ["¿Qué hago si no me llegó el código?",
    "Entra con «Tengo un código de acceso» y usa «¿No te llegó?»: te enviamos uno nuevo al correo registrado. El código es solo tuyo; no lo compartas."],
  ["¿Puedo pagar en cuotas?",
    "Sí. Eliges un convenio de 3 a 24 cuotas y ves el total antes de aceptarlo. Después pagas una cuota o varias juntas, siempre desde la que vence primero."],
  ["¿Cobran intereses?",
    "Depende de lo que pactaste con la empresa. Si tu contrato tiene una tasa, la deuda atrasada y el convenio llevan interés; antes de pagar ves cuánto es capital y cuánto interés. Si no tiene, no se cobra nada extra."],
  ["¿Cómo sé que esto es legítimo?",
    "Adentro, en «Mis datos», ves qué empresa te registró y qué correo tiene de ti. Si no reconoces una deuda, no la pagues: reclámala desde el portal o comunícate directo con esa empresa."],
];

const CODIGO = "K7M2QX";
/** Lo mismo en la orilla lejana: la curva Q de (1180, 18) a (760, 104) con control en (970, 104). */
const alturaLejana = (x) => { const t = (1180 - x) / 420; return 18 * (1 - t) ** 2 + 208 * t * (1 - t) + 104 * t * t; };

const sinMovimiento = () =>
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * La orilla de la empresa: el tablero que llega, la torre de ese lado y su anclaje. Va arriba de la franja
 * de empresas, con el color del texto de la franja. Se dibuja cuando entra en pantalla.
 */
function OrillaLejana() {
  return (
    <svg className="orilla-lejana" viewBox="0 0 1440 120" preserveAspectRatio="xMaxYMax slice" aria-hidden="true" focusable="false">
      <path className="puente-trazo lejana-tablero" pathLength="1" d="M0 104H1440" />
      <path className="puente-trazo lejana-torre" pathLength="1" d="M1180 116V12" />
      <path className="puente-trazo lejana-cable" pathLength="1" d="M1180 18Q970 104 760 104" />
      <path className="puente-trazo lejana-cable" pathLength="1" d="M1180 18Q1290 70 1400 102" style={{ "--d": "0.1s" }} />
      {[880, 960, 1040, 1110].map((x, i) => (
        <path key={x} className="puente-trazo lejana-tirante" pathLength="1"
              d={`M${x} ${alturaLejana(x).toFixed(1)}V104`} style={{ "--d": `${i * 0.05}s` }} />
      ))}
    </svg>
  );
}

/** Las piezas del portal que acompanan cada paso. Son ejemplos: no son datos de nadie. */
function PiezaCorreo() {
  return (
    <div className="pieza pieza-correo">
      <div className="pieza-correo-cab"><span>De: DataBridge</span><span>Hoy, 10:42</span></div>
      <p className="pieza-titulo">Tu código de acceso</p>
      <p className="pieza-texto">Entra al portal con tu RUT o correo y este código:</p>
      <p className="pieza-codigo" aria-label={`Código de ejemplo ${CODIGO}`}>
        {CODIGO.split("").map((c, i) => <span key={i} style={{ "--i": i }}>{c}</span>)}
      </p>
      <p className="pieza-texto">Vence en 15 minutos y sirve una sola vez.</p>
    </div>
  );
}

/** Con las mismas palabras que la entrada de verdad (Login.jsx): quien entra reconoce lo que vio aqui. */
function PiezaEntrada() {
  return (
    <div className="pieza pieza-entrada">
      <p className="pieza-titulo">Entrar</p>
      <span className="pieza-etiqueta">RUT</span>
      <span className="pieza-campo"><span className="pieza-escribe">12.345.678-5</span></span>
      <span className="pieza-boton pieza-boton-sec">Enviar código</span>
      <span className="pieza-etiqueta">Código de acceso</span>
      <span className="pieza-campo pieza-campo-codigo">
        <span className="pieza-escribe pieza-escribe-codigo">{CODIGO}</span>
        <IconoCheck size={18} />
      </span>
      <span className="pieza-boton">Entrar</span>
    </div>
  );
}

function PiezaDeuda({ visto }) {
  return (
    <div className="pieza pieza-deuda">
      <div className="deuda-cab">
        <div>
          <span className="pieza-acreedor">Tu acreedor</span>
          <p className="pieza-titulo">Arriendo de departamento</p>
        </div>
        <div className="deuda-monto">
          <span>Saldo</span>
          <b>$1.050.000</b>
        </div>
      </div>
      {/* Al verla, la barra vuelve a correr desde el principio. */}
      <BarraEstado key={visto ? "vista" : "quieta"} deuda={EJEMPLO} />
    </div>
  );
}

/** El convenio como es de verdad (Repact.jsx): el plazo de 3 a 24 meses, los atajos y la cuota. */
function PiezaPago({ visto }) {
  // Al verla, el plazo pasa de 3 a 6 meses, como cuando uno prueba. Sin interes, para que la cuenta se lea.
  const meses = visto ? 6 : 3;
  const cuota = meses === 6 ? "$175.000" : "$350.000";
  return (
    <div className="pieza pieza-pago">
      <p className="pieza-titulo">Pagar en cuotas</p>
      <div className="plazo pieza-plazo">
        <span className="pieza-plazo-riel"><span className="pieza-plazo-relleno" style={{ "--pct": `${((meses - 3) / 21) * 100}%` }} /></span>
        <div className="plazo-extremos"><span>3 meses</span><span>24 meses</span></div>
      </div>
      <div className="atajos">
        {[3, 6, 12, 18, 24].map((m) => <span key={m} className={m === meses ? "chip on" : "chip"}>{m} meses</span>)}
      </div>
      <div className="cifras pieza-cifras">
        <div className="cifra principal"><span>Cuota mensual</span><b key={cuota}>{cuota}</b></div>
        <div className="cifra"><span>Costo total</span><b>$1.050.000</b></div>
      </div>
      <div className="pieza-pasarelas">
        <LogoPasarela id="webpay" alto={20} />
        <LogoPasarela id="mercadopago" alto={20} />
        <LogoPasarela id="khipu" alto={20} />
      </div>
    </div>
  );
}

const PIEZAS = [
  [PiezaCorreo, "Así llega el correo: trae solo un código, nunca el monto ni un enlace para pagar. Si te llega un enlace que no pediste, no lo abras y escribe tú la dirección del portal."],
  [PiezaEntrada, "La entrada: tu RUT y el código, sin contraseña."],
  [PiezaDeuda, "Cada deuda con su saldo y el avance de tus cuotas."],
  [PiezaPago, "El convenio: eliges el plazo, ves la cuota y el total antes de aceptar, y pagas con la pasarela que prefieras."],
];

/**
 * La portada (#536): un puente colgante visto de costado. El deudor esta en la orilla grande y cruza paso
 * a paso; la empresa tiene su orilla, sin competir con la del deudor. Los tablones se encienden al bajar y,
 * mas abajo, cada paso se arma con una pieza del portal. Todo lo que dice es lo que el sistema hace hoy; la
 * comision para empresas va al pie, en letra chica, como pidio el PO (#278).
 */
export default function Landing() {
  const orillasRef = useRef(null);
  const [encendidos, setEncendidos] = useState(() => (sinMovimiento() ? PASOS.length : 0));
  const [vistos, setVistos] = useState(() => new Set());
  // Solo con JavaScript y movimiento las piezas esperan a entrar en pantalla para armarse.
  const [conMovimiento, setConMovimiento] = useState(false);

  // Los tablones: el primero se enciende cuando termina de dibujarse el puente, y los otros a medida que se
  // baja por la primera pantalla, como si se cruzara.
  useEffect(() => {
    if (sinMovimiento()) return undefined;
    let cuadro = 0;
    const medir = () => {
      cuadro = 0;
      const alto = orillasRef.current?.offsetHeight || window.innerHeight;
      const avance = Math.min(1, Math.max(0, window.scrollY / (alto * 0.55)));
      setEncendidos((antes) => Math.max(antes, 1 + Math.round(avance * (PASOS.length - 1))));
    };
    const alBajar = () => { if (!cuadro) cuadro = requestAnimationFrame(medir); };
    const primero = setTimeout(() => setEncendidos((antes) => Math.max(antes, 1)), 1500);
    window.addEventListener("scroll", alBajar, { passive: true });
    return () => {
      clearTimeout(primero);
      window.removeEventListener("scroll", alBajar);
      if (cuadro) cancelAnimationFrame(cuadro);
    };
  }, []);

  // Cada paso de "Como funciona" se arma cuando entra en pantalla, y la orilla de la empresa y el cierre
  // dibujan su puente en ese momento. Cada uno se marca con data-vista.
  useEffect(() => {
    const pasos = document.querySelectorAll("[data-vista]");
    if (sinMovimiento() || !("IntersectionObserver" in window)) {
      setVistos(new Set([...pasos].map((p) => p.dataset.vista)));
      return undefined;
    }
    setConMovimiento(true);
    const observador = new IntersectionObserver((entradas) => {
      entradas.forEach((e) => {
        if (!e.isIntersecting) return;
        const vista = e.target.dataset.vista;
        setVistos((antes) => (antes.has(vista) ? antes : new Set(antes).add(vista)));
        observador.unobserve(e.target);
      });
    }, { rootMargin: "0px 0px -20% 0px" });
    pasos.forEach((p) => observador.observe(p));
    return () => observador.disconnect();
  }, []);

  return (
    <div className={conMovimiento ? "portada con-movimiento" : "portada"}>
      <a className="saltar" href="#contenido">Saltar al contenido</a>
      <header className="portada-barra">
        <Link to="/" className="brand-row portada-marca" aria-label="DataBridge, inicio">
          <Logo size={40} />
          <span className="brand-name">DataBridge</span>
        </Link>
        <nav className="portada-nav" aria-label="Secciones">
          <a href="#como-funciona">Cómo funciona</a>
          <a href="#seguro">Seguridad</a>
          <a href="#empresas">Para empresas</a>
          <a href="#preguntas">Preguntas</a>
        </nav>
        <div className="portada-barra-acciones">
          <TemaToggle />
          <Link className="btn btn-primary btn-sm" to="/login">Entrar</Link>
        </div>
      </header>

      <main id="contenido">
        <section className="orillas" ref={orillasRef} aria-labelledby="titulo-portada">
          {/* Orden de lectura: el deudor, sus pasos y al final la empresa. En el computador la grilla pone a la
              empresa arriba a la derecha, en su orilla. */}
          <div className="orilla-deudor">
            <h1 id="titulo-portada" className="aparece">Ponte al día, sin llamadas ni filas</h1>
            <p className="portada-bajada aparece" style={{ "--i": 1 }}>
              Entras con tu RUT y el código que te llegó por correo, ves cuánto debes y a quién, y pagas todo de una
              vez o en cuotas.
            </p>
            <Link className="btn btn-primary portada-cta aparece" style={{ "--i": 2 }} to="/login">
              Tengo un código de acceso
              <IconoFlecha size={18} />
            </Link>
          </div>

          <Puente />

          <ol className="tablones" aria-label="Los cuatro pasos">
            {TABLONES.map(([titulo, texto], i) => (
              <li key={titulo} className={i < encendidos ? "tablon encendido" : "tablon"}>
                <b>{titulo}</b>
                <span>{texto}</span>
              </li>
            ))}
          </ol>

          <aside className="orilla-empresa aparece" style={{ "--i": 3 }} aria-labelledby="titulo-orilla-empresa">
            <h2 id="titulo-orilla-empresa">¿Cobras deudas?</h2>
            <p>DataBridge les escribe a tus deudores, dentro de la ley.</p>
            <Link className="btn btn-ghost" to="/databridge/login">Entrar como empresa</Link>
          </aside>
        </section>

        <section id="como-funciona" className="cruce" aria-labelledby="titulo-como">
          <div className="cruce-cab">
            <h2 id="titulo-como">Cómo funciona</h2>
            <p className="portada-intro">Desde que te llega el código hasta que tu deuda queda en cero, todo en el portal.</p>
          </div>
          <ol className="cruce-pasos">
            {PASOS.map(([titulo, texto], i) => {
              const [Pieza, pie] = PIEZAS[i];
              return (
                <li key={titulo} className={vistos.has(String(i)) ? "cruce-paso visto" : "cruce-paso"} data-vista={i}>
                  <div className="cruce-texto">
                    <span className="cruce-n" aria-hidden="true">{i + 1}</span>
                    <h3>{titulo}</h3>
                    <p>{texto}</p>
                  </div>
                  <figure className="cruce-pieza">
                    <Pieza visto={vistos.has(String(i))} />
                    <figcaption>{pie} <span className="cruce-ejemplo">Ejemplo.</span></figcaption>
                  </figure>
                </li>
              );
            })}
          </ol>
        </section>

        <section id="seguro" className="portada-seccion portada-seguro" aria-labelledby="titulo-seguro">
          <div>
            <h2 id="titulo-seguro">Pagas seguro</h2>
            <p className="portada-intro">
              Entras sin contraseña, con un código de un solo uso, y pagas con Webpay, Mercado Pago o Khipu.
            </p>
            <div className="portada-pasarelas" aria-label="Pasarelas de pago">
              <LogoPasarela id="webpay" alto={22} />
              <LogoPasarela id="mercadopago" alto={22} />
              <LogoPasarela id="khipu" alto={22} />
            </div>
          </div>
          <ul className="portada-garantias">
            {GARANTIAS.map(([titulo, texto]) => (
              <li key={titulo}>
                <h3>{titulo}</h3>
                <p>{texto}</p>
              </li>
            ))}
          </ul>
        </section>

        <section id="empresas" className={vistos.has("empresas") ? "otra-orilla visto" : "otra-orilla"}
                 data-vista="empresas" aria-labelledby="titulo-empresas">
          <OrillaLejana />
          <div className="otra-orilla-dentro">
            <div>
              <h2 id="titulo-empresas">Para empresas que cobran</h2>
              <p className="portada-intro">
                Acreedores y agencias de cobranza de cualquier rubro: arriendos, aranceles, tratamientos o planes
                mensuales. Tú decides la estrategia; DataBridge la cumple.
              </p>
              <Link className="btn portada-btn-claro" to="/databridge/login">
                Entrar como empresa
                <IconoFlecha size={16} />
              </Link>
            </div>
            <ul className="portada-lista">
              {PARA_EMPRESAS.map((texto) => <li key={texto}>{texto}</li>)}
            </ul>
          </div>
        </section>

        <section id="preguntas" className="portada-seccion portada-preguntas" aria-labelledby="titulo-preguntas">
          <h2 id="titulo-preguntas">Preguntas frecuentes</h2>
          <div className="preguntas">
            {PREGUNTAS.map(([pregunta, respuesta]) => (
              <details key={pregunta}>
                <summary>{pregunta}</summary>
                <p>{respuesta}</p>
              </details>
            ))}
          </div>
        </section>
        <section className={vistos.has("cierre") ? "cierre visto" : "cierre"} data-vista="cierre"
                 aria-labelledby="titulo-cierre">
          <div className="cierre-texto">
            <h2 id="titulo-cierre">Del otro lado, quedas al día</h2>
            <p className="portada-intro">
              Cuando pagas la última cuota, la deuda queda pagada y descargas tu certificado de deuda pagada.
            </p>
            <Link className="btn btn-primary portada-cta" to="/login">
              Tengo un código de acceso
              <IconoFlecha size={18} />
            </Link>
          </div>
          <figure className="cruce-pieza cierre-pieza">
            <div className="pieza pieza-deuda">
              <div className="deuda-cab">
                <div>
                  <span className="pieza-acreedor">Tu acreedor</span>
                  <p className="pieza-titulo">Arriendo de departamento</p>
                </div>
                <div className="deuda-monto">
                  <span>Saldo</span>
                  <b>$0</b>
                </div>
              </div>
              <BarraEstado key={vistos.has("cierre") ? "vista" : "quieta"} deuda={EJEMPLO_PAGADA} />
            </div>
            <figcaption>La misma deuda, con las seis cuotas pagadas. <span className="cruce-ejemplo">Ejemplo.</span></figcaption>
          </figure>
        </section>
      </main>

      <footer className="portada-pie">
        <div className="portada-pie-fila">
          <div className="brand-row">
            <Logo size={32} />
            <div>
              <div className="brand-name">DataBridge</div>
              <div className="brand-sub">Pago de deudas en cobranza</div>
            </div>
          </div>
          <nav className="portada-pie-enlaces" aria-label="Entrar">
            <Link to="/login">Tengo un código de acceso</Link>
            <Link to="/databridge/login">Soy de una empresa</Link>
          </nav>
        </div>
        <p className="letra-chica">
          Comisión para empresas: 6 % del total ingresado por pagos en el mes si suma hasta $1.000.000; 10 % si suma más de
          $1.000.000 y hasta $5.000.000; 12 % si suma más de $5.000.000 y hasta $10.000.000, y 15 % si suma más de
          $10.000.000. El porcentaje se aplica sobre el total de pagos del mes y lo paga la empresa que cargó la
          deuda. Al deudor no se le cobra nada por usar DataBridge.
        </p>
        <p className="letra-chica">© 2026 DataBridge.</p>
      </footer>
    </div>
  );
}
