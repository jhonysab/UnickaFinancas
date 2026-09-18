package com.familia.controledegastos

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.familia.controledegastos.model.Cartao
import com.familia.controledegastos.model.CategoriasPadrao
import com.familia.controledegastos.model.FormaPagamento
import com.familia.controledegastos.model.Recorrencia
import com.familia.controledegastos.model.TipoTransacao
import com.familia.controledegastos.model.Transacao
import com.familia.controledegastos.model.Usuario
import com.familia.controledegastos.ui.TransacoesViewModel
import com.familia.controledegastos.ui.telas.TelaCartoes
import com.familia.controledegastos.ui.telas.TelaNovaTransacao
import com.familia.controledegastos.ui.telas.TelaPrincipal
import com.familia.controledegastos.ui.theme.ControleDeGastosTheme
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Prints para divulgação: o app de verdade (TelaPrincipal + ViewModel +
// repositórios) rodando com uma família de mentira.
// O Firestore fica OFFLINE: os dados só existem no cache local deste
// aparelho e são apagados no fim — nada chega na nuvem.
@RunWith(AndroidJUnit4::class)
class PrintsDivulgacaoTest {

    @get:Rule
    val regra = createComposeRule()

    private val db = FirebaseFirestore.getInstance()
    private val familia = "familia-demo"
    private val mes = YearMonth.now()

    private fun print(nome: String) {
        regra.waitForIdle()
        Thread.sleep(800) // deixa as animações (abas, barras) assentarem
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$nome.png")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun dia(d: LocalDate) =
        Timestamp(Date.from(d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()))

    private fun t(
        dia: LocalDate, descricao: String, reais: Double, categoria: String,
        quem: String, forma: FormaPagamento = FormaPagamento.PIX, cartao: String = "",
        tipo: TipoTransacao = TipoTransacao.GASTO, recorrencia: String = ""
    ) = Transacao(
        tipo = tipo, valorCentavos = Math.round(reais * 100), categoria = categoria,
        descricao = descricao, data = dia(dia), criadoPor = quem,
        recorrenciaId = recorrencia, formaPagamento = forma, cartaoId = cartao
    )

    @Before
    fun semear(): Unit = runBlocking {
        db.disableNetwork().await()
        // sem await nos set(): offline eles só confirmam quando o servidor
        // responde, mas o cache local (e os ouvintes) já veem na hora
        val base = db.collection("familias").document(familia)

        listOf(Usuario("u1", "João", familia), Usuario("u2", "Maria", familia)).forEach {
            db.collection("usuarios").document(it.id).set(it)
        }
        // na aba Limites só ficam as que têm limite definido
        val semLimite = setOf("CASA", "INTERNET", "AGUA", "TRANSPORTE", "OUTROS")
        CategoriasPadrao.lista.forEach {
            base.collection("categorias").document(it.id).set(it.copy(ocultaNosLimites = it.id in semLimite))
        }
        listOf(
            Cartao("nubank", "Nubank", diaVencimento = 12, diaFechamento = 5, limiteCentavos = 300_000),
            Cartao("inter", "Inter", diaVencimento = 5, diaFechamento = 25, limiteCentavos = 150_000)
        ).forEach { base.collection("cartoes").document(it.id).set(it) }
        mapOf(
            "MERCADO" to 120_000L, "GASOLINA" to 50_000L, "LAZER" to 25_000L,
            "SAUDE" to 20_000L, "LUZ" to 20_000L
        ).forEach { (id, v) -> base.collection("orcamentos").document(id).set(mapOf("limiteCentavos" to v)) }
        listOf(
            Recorrencia("aluguel", "Aluguel", TipoTransacao.GASTO, "CASA", 120_000, 5),
            Recorrencia("luz", "Conta de luz", TipoTransacao.GASTO, "LUZ", 18_000, 10),
            Recorrencia("agua", "Água", TipoTransacao.GASTO, "AGUA", 9_000, 12),
            Recorrencia("net", "Internet", TipoTransacao.GASTO, "INTERNET", 12_000, 25),
            Recorrencia("sal1", "Salário João", TipoTransacao.GANHO, "SALARIO", 350_000, 5),
            Recorrencia("sal2", "Salário Maria", TipoTransacao.GANHO, "SALARIO", 280_000, 5)
        ).forEach { base.collection("recorrencias").document(it.id).set(it) }

        val d = { n: Int -> mes.atDay(n) }
        val ganho = TipoTransacao.GANHO
        val atual = listOf(
            t(d(5), "Salário João", 3500.0, "SALARIO", "u1", tipo = ganho, recorrencia = "sal1"),
            t(d(5), "Salário Maria", 2800.0, "SALARIO", "u2", tipo = ganho, recorrencia = "sal2"),
            t(d(5), "Aluguel", 1200.0, "CASA", "u1", recorrencia = "aluguel"),
            t(d(6), "Mercado do mês", 642.80, "MERCADO", "u2", FormaPagamento.CREDITO, "nubank"),
            t(d(8), "Posto", 200.0, "GASOLINA", "u1", FormaPagamento.DEBITO),
            t(d(9), "Farmácia", 58.90, "SAUDE", "u2"),
            t(d(10), "Conta de luz", 176.40, "LUZ", "u1", FormaPagamento.DEBITO, recorrencia = "luz"),
            t(d(12), "Água", 87.30, "AGUA", "u2", recorrencia = "agua"),
            t(d(13), "Cinema", 96.00, "LAZER", "u2", FormaPagamento.CREDITO, "inter"),
            t(d(14), "Feira", 72.50, "MERCADO", "u2", FormaPagamento.DINHEIRO),
            t(d(15), "Pizza em família", 119.00, "LAZER", "u1", FormaPagamento.CREDITO, "inter"),
            t(d(16), "Padaria", 23.40, "MERCADO", "u1", FormaPagamento.DINHEIRO),
            t(d(17), "Gasolina", 150.0, "GASOLINA", "u1", FormaPagamento.CREDITO, "nubank"),
            t(d(18), "Mercado", 186.35, "MERCADO", "u2", FormaPagamento.DEBITO)
        ).filter { !it.data.toDate().after(Date()) } // nada no futuro

        // meses anteriores, só para as barras dos Gráficos
        val gastosAntigos = listOf(4870.0, 5320.0, 4410.0, 5950.0, 4980.0)
        val anteriores = gastosAntigos.flatMapIndexed { i, gasto ->
            val m = mes.minusMonths((5 - i).toLong())
            listOf(
                t(m.atDay(5), "Salários", 6300.0, "SALARIO", "u1", tipo = ganho),
                t(m.atDay(6), "Aluguel", 1200.0, "CASA", "u1"),
                t(m.atDay(10), "Mercado", gasto * 0.4, "MERCADO", "u2"),
                t(m.atDay(15), "Contas da casa", gasto * 0.2, "LUZ", "u1"),
                t(m.atDay(20), "Outros", gasto - 1200 - gasto * 0.6, "OUTROS", "u2")
            )
        }
        (atual + anteriores).forEach { base.collection("transacoes").document().set(it) }
    }

    @After
    fun limpar(): Unit = runBlocking {
        // apaga o cache (e as escritas pendentes) para nada subir depois
        db.terminate().await()
        db.clearPersistence().await()
        Unit
    }

    @Test
    fun telas_para_divulgacao() {
        val vm = TransacoesViewModel(familiaId = familia, uid = "u1")
        var tela by mutableStateOf("principal")
        regra.setContent {
            ControleDeGastosTheme {
                when (tela) {
                    "principal" -> TelaPrincipal(
                        usuario = Usuario("u1", "João", familia),
                        aoSair = {}, aoAtualizarNome = {}, vm = vm
                    )
                    "cartoes" -> TelaCartoes(
                        cartoes = vm.cartoes, faturaDe = vm::faturaAtualCentavos,
                        aoSalvar = {}, aoRemover = {}, aoVoltar = {}
                    )
                    else -> TelaNovaTransacao(
                        cartoes = vm.cartoes,
                        categoriasGasto = vm.categoriasParaGasto,
                        categoriasGanho = vm.categoriasParaGanho,
                        aoSalvar = {}, aoCancelar = {},
                        valorInicialCentavos = 18_635,
                        categoriaInicial = vm.resolverCategoria("MERCADO"),
                        descricaoInicial = "Mercado",
                        formaPagamentoInicial = FormaPagamento.CREDITO,
                        cartaoIdInicial = "nubank"
                    )
                }
            }
        }
        regra.waitUntil(10_000) {
            vm.transacoes.isNotEmpty() && vm.membros.size == 2 &&
                vm.categorias.isNotEmpty() && vm.cartoes.size == 2
        }

        print("1-resumo")
        regra.onNodeWithText("Gráficos").performClick()
        print("2-graficos")
        regra.onNodeWithText("Limites").performClick()
        print("3-limites")
        regra.onNodeWithText("Contas").performClick()
        print("4-contas")

        tela = "lancar"
        print("5-lancar")
        tela = "cartoes"
        print("6-cartoes")
    }
}
