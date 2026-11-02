import { Link } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import BarraEstado from "../components/BarraEstado";
import LogoPasarela from "../components/LogoPasarela";
import { IconoCheck, IconoFlecha } from "../components/Iconos";

/**
 * Un ejemplo de lo que el deudor ve adentro: un arriendo en 6 cuotas, con la
 * primera pagada. Generico a proposito: no es una deuda ni una empresa de la demo.
 */
const EJEMPLO = { estado: "repacted", cuotasPagadas: 1, cuotasTotales: 6, conConvenio: true };

/** Lo que pasa desde que llega el codigo hasta pagar (lo mismo que dice Como funciona, adentro). */
const PASOS = [
  ["Te llega un código", "Al correo que tiene registrado la empresa a la que le debes. Sirve una sola vez."],
  ["Entras con tu RUT", "Sin crear una cuenta ni recordar una contraseña: tu RUT y el código."],
  ["Ves lo que debes", "Cada deuda con su monto, a quién se la debes y los meses que incluye."],
  ["Pagas como te acomode", "Todo de una vez, o en un convenio de 3 a 24 cuotas que aceptas ahí mismo."],
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
  "Ves en qué va cada deuda y cada pago conciliado, y lo descargas en una planilla.",
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

/**
 * La portada: que es DataBridge, como funciona, por que es seguro pagar aqui y
 * que hace por una empresa. Todo lo que dice es lo que el sistema hace hoy; la
 * comision para empresas va al pie, en letra chica, como pidio el PO (#278).
 */
export default function Landing() {
  return (
    <div className="portada">
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
          <Link className="btn btn-ghost btn-sm portada-solo-ancho" to="/databridge/login">Soy de una empresa</Link>
          <Link className="btn btn-primary btn-sm" to="/login">Entrar</Link>
        </div>
      </header>

      <main>
        <section className="portada-hero" aria-labelledby="titulo-portada">
          <div className="portada-hero-texto">
            <h1 id="titulo-portada" className="aparece">Ponte al día con tus deudas, sin llamadas ni filas</h1>
            <p className="portada-bajada aparece" style={{ "--i": 1 }}>
              Entras con tu RUT y el código que te llegó por correo. Ves cuánto debes y a quién, sea un arriendo, un
              arancel o un tratamiento, y decides si pagas todo de una vez o en cuotas.
            </p>
            <div className="portada-acciones aparece" style={{ "--i": 2 }}>
              <Link className="btn btn-primary" to="/login">
                Tengo un código de acceso
                <IconoFlecha size={16} />
              </Link>
              <Link className="btn btn-ghost" to="/databridge/login">Soy de una empresa</Link>
            </div>
            <p className="portada-aviso aparece" style={{ "--i": 3 }}>
              Los avisos de DataBridge traen solo un código: nunca el monto ni un enlace para pagar. Si te llega un
              enlace que no pediste, no lo abras y escribe tú la dirección del portal.
            </p>
          </div>

          <figure className="portada-ejemplo aparece" style={{ "--i": 2 }}>
            <div className="card">
              <div className="deuda-cab" style={{ marginBottom: 18 }}>
                <div>
                  <span className="portada-ejemplo-acreedor">Tu acreedor</span>
                  <h2 className="portada-ejemplo-titulo">Arriendo de departamento</h2>
                </div>
                <div className="deuda-monto">
                  <span>Saldo</span>
                  <b style={{ fontSize: 24 }}>$1.050.000</b>
                </div>
              </div>
              <BarraEstado deuda={EJEMPLO} />
            </div>
            <figcaption>Ejemplo: así ves cada deuda, con el avance de tus cuotas.</figcaption>
          </figure>
        </section>

        <section id="como-funciona" className="portada-seccion" aria-labelledby="titulo-como">
          <h2 id="titulo-como">Cómo funciona</h2>
          <p className="portada-intro">Desde que te llega el código hasta que tu deuda queda en cero, todo en el portal.</p>
          <ol className="portada-pasos">
            {PASOS.map(([titulo, texto], i) => (
              <li key={titulo}>
                <span className="portada-paso-n" aria-hidden="true">{i + 1}</span>
                <h3>{titulo}</h3>
                <p>{texto}</p>
              </li>
            ))}
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
                <span className="portada-check" aria-hidden="true"><IconoCheck size={16} /></span>
                <div>
                  <h3>{titulo}</h3>
                  <p>{texto}</p>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section id="empresas" className="portada-empresas" aria-labelledby="titulo-empresas">
          <div className="portada-empresas-dentro">
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
          Comisión para empresas: 6 % de lo recuperado en el mes si suma hasta $1.000.000; 10 % si suma más de
          $1.000.000 y hasta $5.000.000; 12 % si suma más de $5.000.000 y hasta $10.000.000, y 15 % si suma más de
          $10.000.000. El porcentaje se aplica sobre todo lo recuperado en el mes y lo paga la empresa que cargó la
          deuda. Al deudor no se le cobra nada por usar DataBridge.
        </p>
        <p className="letra-chica">© 2026 DataBridge.</p>
      </footer>
    </div>
  );
}
