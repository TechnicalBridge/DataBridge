import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { opcionesDeCarga } from "../api/deudas";
import BarraEstado from "../components/BarraEstado";

const EJEMPLO = { estado: "repacted", cuotasPagadas: 2, cuotasTotales: 6, conConvenio: true };

/** Como funciona el portal, contado para quien lo usa: el deudor o la empresa. */
export default function ComoFunciona({ para }) {
  return para === "empresa" ? <ParaEmpresas /> : <ParaDeudores />;
}

function Pasos({ pasos }) {
  return (
    <ol className="guia">
      {pasos.map(([titulo, texto], i) => (
        <li key={titulo} className="aparece" style={{ "--i": i + 1 }}>
          <span className="n">{i + 1}</span>
          <div>
            <b>{titulo}</b>
            <p>{texto}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

function Preguntas({ preguntas }) {
  return (
    <div className="preguntas">
      {preguntas.map(([pregunta, respuesta]) => (
        <details key={pregunta}>
          <summary>{pregunta}</summary>
          <p>{respuesta}</p>
        </details>
      ))}
    </div>
  );
}

function ParaDeudores() {
  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Cómo funciona</h1>
          <p>Desde que te llega el código hasta que tu deuda queda en cero.</p>
        </div>
      </header>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <Pasos pasos={[
            ["Te llega un código", "La empresa a la que le debes, o la agencia que cobra por ella, te manda un código de seis caracteres por correo. Nunca un enlace."],
            ["Entras con tu RUT y el código", "Escribes tú la dirección del portal. El código sirve una vez y dura 24 horas; si se te vence, pides otro."],
            ["Ves lo que debes", "Cada deuda con su monto, a quién se la debes y los meses que incluye."],
            ["Pagas como te acomode", "Todo de una vez, o en un convenio de 3 a 24 cuotas. En convenio puedes pagar una cuota o varias juntas, siempre desde la que vence primero."],
            ["Queda conciliado", "Cuando la pasarela confirma el pago, se abona a tu deuda y la empresa recibe el aviso. Cada pago tiene su comprobante y, al terminar, descargas el certificado de deuda pagada."],
          ]} />
        </div>

        <div>
          <div className="card aparece" style={{ "--i": 2, marginBottom: 16 }}>
            <h3>Tu deuda avanza por tres estados</h3>
            <BarraEstado deuda={EJEMPLO} />
            <p className="hint">
              Pendiente mientras no hagas nada; en convenio si eliges pagar en cuotas, y la barra avanza con
              cada una; pago conciliado cuando terminas.
            </p>
          </div>
          <div className="card aparece" style={{ "--i": 3 }}>
            <h3>Preguntas frecuentes</h3>
            <Preguntas preguntas={[
              ["¿Por qué no me mandan un enlace para entrar?", "Porque un enlace en un correo es justo lo que usan las estafas para llevarte a una página falsa. Como nosotros nunca mandamos uno, cualquier mensaje con enlace que diga venir de aquí es falso."],
              ["¿Cobran intereses?", "Depende de lo que pactaste con la empresa. Si tu contrato tiene una tasa de interés, una deuda atrasada crece por cada día de atraso y el convenio lleva interés; el portal te muestra cuánto es capital y cuánto interés antes de pagar. Si no tiene tasa, no se cobra nada extra: el total del convenio es lo que debes hoy."],
              ["¿Puedo pagar varias cuotas a la vez?", "Sí, siempre desde la que vence primero. Así nunca queda una cuota antigua impaga mientras pagas una nueva."],
              ["Mi deuda está en UF: ¿cuánto pago en pesos?", "Pagas en pesos, al valor de la UF del día en que pagas. El comprobante dice qué UF se usó."],
              ["¿Hay descuento si pago todo?", "A veces. Si la empresa tiene una campaña con descuento, al pagar toda la deuda de una vez te descontamos parte de los intereses por mora: más mientras más antigua es la deuda. Lo ves en Mis deudas y en el detalle antes de pagar. No aplica si pagas en cuotas, y el capital nunca se descuenta."],
              ["¿Cómo sé que esto es legítimo?", "En Mis datos ves qué empresa te registró y el correo que tiene de ti. Si no reconoces la deuda, no pagues y comunícate directo con esa empresa."],
              ["¿Qué pasa si se me atrasa una cuota?", "La empresa ve que el convenio está atrasado y te va a contactar. Mientras antes pagues la cuota vencida, mejor."],
              ["¿Me van a recordar las cuotas?", "Sí, por correo, unos días antes de cada vencimiento. Puedes apagar esos avisos en Mis datos."],
            ]} />
          </div>
        </div>
      </div>

      <p className="hint">
        Tus cuotas por fecha están en <Link className="link-btn" to="/app/vencimientos">Próximos vencimientos</Link>, y
        lo que ya pagaste en <Link className="link-btn" to="/app/pagos">Historial de pagos</Link>.
      </p>
    </div>
  );
}

function ParaEmpresas() {
  const [minimo, setMinimo] = useState(30);

  useEffect(() => {
    opcionesDeCarga().then((o) => setMinimo(o.minDiasMora ?? 30)).catch(() => {});
  }, []);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Cómo funciona</h1>
          <p>De la planilla de morosos al pago conciliado.</p>
        </div>
      </header>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <Pasos pasos={[
            ["Creas una campaña", "Le das un nombre, sus fechas y los días en que se le escribe al deudor. Se crea en Campañas o en el primer paso de la carga."],
            ["Cargas a tus deudores", "En Cargar cartera subes la planilla a la campaña. Antes de confirmar ves cuántas deudas entrarían, cuánto suman y cuáles no, con su motivo. Recién al confirmar entran."],
            ["Entran solo morosos", `Una deuda entra cuando su cargo impago más antiguo lleva ${minimo} días vencido, sea un arriendo mensual, un arancel o un tratamiento de un solo cargo. Las que no cumplen se rechazan solas, con su motivo: DataBridge no cobra lo que todavía no es mora.`],
            ["El deudor recibe su código", "Apenas entra su deuda, DataBridge le escribe a su correo con el código para entrar al portal, y la campaña le vuelve a escribir los días que elegiste. Si lo pierde, se lo reenvías desde la cartera. El código no se muestra aquí: quien lo viera podría entrar en su lugar."],
            ["Paga o pide un convenio", "El deudor paga todo o en 3 a 24 cuotas, con la tasa de interés que traiga la deuda o sin interés si no trae. Tú ves en qué va cada deuda: pendiente, en convenio o pago conciliado."],
            ["Ves cada pago", "Cuando la pasarela confirma un pago, se abona solo a la deuda. Lo ves en Pagos recibidos, y lo descargas en una planilla."],
          ]} />
        </div>

        <div>
          <div className="card aparece" style={{ "--i": 2, marginBottom: 16 }}>
            <h3>Para empezar</h3>
            <p style={{ margin: 0 }}>
              Todo deudor entra en una campaña. Al cargar la eliges, o la creas ahí mismo, y subes la planilla; la
              plantilla se descarga en el mismo paso.
            </p>
            <div className="acciones-fila" style={{ marginTop: 12 }}>
              <Link className="btn btn-primary btn-sm" to="/databridge/cargar">Cargar deudores</Link>
              <Link className="btn btn-ghost btn-sm" to="/databridge/campanas">Ver las campañas</Link>
            </div>
          </div>
          <div className="card aparece" style={{ "--i": 3, marginBottom: 16 }}>
            <h3>Campañas</h3>
            <p style={{ margin: 0 }}>
              En <Link className="link-btn" to="/databridge/campanas">Campañas</Link> decides cuántas veces se le escribe
              al deudor y qué días, contados desde que su deuda entra (por ejemplo 1, 4, 11). DataBridge envía cada
              correo y deja de escribir cuando el deudor paga, repacta, reclama o la deuda sale de la cobranza.
            </p>
            <p className="hint">
              La ley (art. 37 de la Ley 19.496) pone el límite: como máximo 2 contactos por semana, con al menos 2 días
              entre uno y otro, de lunes a sábado entre 8:00 y 20:00 y nunca en feriados. Un contacto que no cabe se
              corre al siguiente momento permitido. Pausar una campaña detiene los correos; reanudarla los retoma.
            </p>
          </div>
          <div className="card aparece" style={{ "--i": 4 }}>
            <h3>Preguntas frecuentes</h3>
            <Preguntas preguntas={[
              ["¿Por qué se rechazó una deuda?", `La revisión te lo dice antes de confirmar, y cada rechazo viene con su motivo. Los más comunes: bajo_umbral_mora (menos de ${minimo} días de mora), cargo_no_vencido (un cargo todavía no vence a la fecha de corte), rut_invalido y sin_canal_contacto (el deudor no trae correo ni teléfono). Un correo mal escrito no rechaza la deuda: entra sin correo, con el aviso correo_invalido, y en la cartera dice Sin correo válido.`],
              ["¿Qué es un convenio en riesgo?", "Un convenio con cuotas vencidas sin pagar. Aparecen en Convenios en riesgo, del más atrasado al menos, para que contactes al deudor antes de que el convenio se caiga."],
              ["¿Qué pasa si el deudor paga en nuestra oficina?", "Cárgala de nuevo con el saldo menor, o en una fila con acción retirar y el motivo pago_directo. DataBridge deja de cobrarla en el acto."],
              ["¿Podemos ver el código del deudor?", "No. El código va directo al correo del deudor y nunca vuelve a la empresa."],
              ["¿Cómo exporto la cartera o los pagos?", "La cartera, los pagos recibidos y los convenios en riesgo tienen un botón para descargarlos en una planilla que abre Excel."],
            ]} />
          </div>
        </div>
      </div>
    </div>
  );
}
