package com.tbridge.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A quien se le cree cuando dice de que IP viene una peticion.
 *
 * El limitador de peticiones cuenta por IP. Si la IP se puede inventar, el
 * limite no existe; y si todas las peticiones parecen venir de la misma, un
 * solo abusador deja afuera a todos.
 */
class ClienteRealTest {

    /** El mismo patron que trae el gateway por omision. */
    private final ClienteReal clientes =
            new ClienteReal("127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|172\\.(1[6-9]|2[0-9]|3[01])\\..*");

    /** El patron tal como lo trae application.properties, si nadie define TRUSTED_PROXIES. */
    private static ClienteReal comoViene() throws IOException {
        String crudo = String.valueOf(new PropertiesPropertySourceLoader()
                .load("configuracion", new ClassPathResource("application.properties"))
                .getFirst()
                .getProperty("spring.cloud.gateway.server.webflux.trusted-proxies"));
        //  ${TRUSTED_PROXIES:<patron>}: lo que va despues de los dos puntos, sin la llave final.
        return new ClienteReal(crudo.substring(crudo.indexOf(':') + 1, crudo.length() - 1));
    }

    @Test
    void el_portal_que_llega_por_la_red_privada_de_railway_es_de_confianza() throws IOException {
        ClienteReal porOmision = comoViene();

        //  Asi escribe Java una direccion privada de IPv6, la que tiene el portal en Railway.
        assertEquals("203.0.113.7", porOmision.resolver("fd12:3456:789a:1:0:0:0:1", "203.0.113.7"));
        assertEquals("203.0.113.7", porOmision.resolver("10.20.30.40", "203.0.113.7"));
        assertEquals("203.0.113.7", porOmision.resolver("172.21.0.10", "203.0.113.7"));
    }

    @Test
    void una_direccion_publica_no_es_de_confianza_tampoco_en_ipv6() throws IOException {
        ClienteReal porOmision = comoViene();

        assertEquals("198.51.100.4", porOmision.resolver("198.51.100.4", "1.2.3.4"));
        assertEquals("2001:db8:0:0:0:0:0:1", porOmision.resolver("2001:db8:0:0:0:0:0:1", "1.2.3.4"));
        assertEquals("fe80:0:0:0:0:0:0:1", porOmision.resolver("fe80:0:0:0:0:0:0:1", "1.2.3.4"),
                "una de enlace local no es una red privada");
    }

    @Test
    void detras_del_nginx_cuenta_la_ip_que_vio_nginx() {
        //  El nginx del portal (172.21.0.10) sobrescribe la cabecera con la IP real.
        assertEquals("203.0.113.7", clientes.resolver("172.21.0.10", "203.0.113.7"));
    }

    @Test
    void quien_le_habla_directo_no_puede_inventarse_la_ip() {
        //  Un cliente cualquiera manda la cabecera escrita por el mismo: no vale.
        assertEquals("198.51.100.4", clientes.resolver("198.51.100.4", "1.2.3.4"));
    }

    @Test
    void cambiar_la_cabecera_en_cada_intento_no_da_un_cupo_nuevo() {
        //  El ataque que cerraba este arreglo: mil valores distintos, una sola IP.
        for (int i = 0; i < 1000; i++) {
            assertEquals("198.51.100.4", clientes.resolver("198.51.100.4", "10.0.0." + i));
        }
    }

    @Test
    void un_proxy_de_confianza_sin_cabecera_es_el_mismo_el_cliente() {
        //  El proxy de Vite en desarrollo no la manda.
        assertEquals("127.0.0.1", clientes.resolver("127.0.0.1", null));
        assertEquals("127.0.0.1", clientes.resolver("127.0.0.1", "  "));
    }

    @Test
    void con_varios_saltos_cuenta_el_primero() {
        assertEquals("203.0.113.7", clientes.resolver("172.21.0.10", "203.0.113.7, 172.21.0.3"));
    }

    @Test
    void una_red_parecida_pero_de_afuera_no_es_de_confianza() {
        //  172.32 ya no es una red privada: el patron no la acepta.
        assertEquals("172.32.0.9", clientes.resolver("172.32.0.9", "1.2.3.4"));
        assertEquals("192.168.1.20", clientes.resolver("192.168.1.20", "1.2.3.4"));
    }

    @Test
    void sin_par_no_se_inventa_nada() {
        assertEquals("desconocido", clientes.resolver(null, "1.2.3.4"));
    }
}
