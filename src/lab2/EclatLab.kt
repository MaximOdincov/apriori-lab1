package lab2

// Лабораторная работа 2. Поиск ассоциативных правил алгоритмом ECLAT.
//
// ECLAT (Equivalence CLAss Transformation) ищет частые наборы в «вертикальном»
// представлении данных: каждому товару сопоставляется множество номеров корзин
// (tidset), в которых он встречается. Поддержка набора равна размеру пересечения
// tidset'ов его товаров, поэтому база сканируется один раз в начале, а дальше
// работают только операции над множествами (у Apriori — сканирование всей базы
// на каждом уровне).
//
// Параметры программы:
//   * порог поддержки (в эксперименте зафиксирован, 0.5%: при 10% на baskets.csv
//     не выживает ни одна пара — правил не существует);
//   * порог достоверности (в эксперименте варьируется 70%..95% с шагом 5%);
//   * упорядочивание списка правил: по убыванию поддержки или лексикографическое;
//   * суммарный предел размера правила |антецедент| + |консеквент| <= 7.

import java.io.File
import java.util.Locale
import kotlin.math.ceil

fun main() {
    val baskets = readBaskets("src/data/baskets.csv")

    val fixedSupport = 0.002   // зафиксированный порог поддержки (0.2% = 16 транзакций)
    val bySupport = true       // true — правила по убыванию поддержки, false — лексикографически
    val maxObjects = 7         // суммарный размер антецедент + консеквент

    val minSupport = ceil(fixedSupport * baskets.size).toInt()
    println("Корзин: ${baskets.size}, разных товаров: ${baskets.flatten().distinct().size}")
    println("Зафиксированный порог поддержки: $fixedSupport (>= $minSupport транзакций)")

    // --- Поиск частых наборов (ECLAT) — от порога достоверности не зависит ---
    val start = System.nanoTime()
    val found = eclat(baskets, minSupport)
    val miningMs = (System.nanoTime() - start) / 1_000_000
    val byLength = found.keys.groupingBy { it.size }.eachCount().toSortedMap()
    val details = byLength.entries.joinToString(", ") { (len, n) -> "длина $len: $n" }
    println("Частых наборов: ${found.size} ($details), поиск ECLAT занял $miningMs мс")
    println()

    generateRules(found, baskets.size, 0.70, maxObjects)   // прогрев JVM перед замерами

    // --- Эксперимент: варьируем порог достоверности 70%..95% с шагом 5% ---
    val thresholds = (70..95 step 5).toList()
    val labels = mutableListOf<String>()
    val times = mutableListOf<Double>()
    val counts = mutableListOf<Int>()

    for (conf in thresholds) {
        val t0 = System.nanoTime()
        val rules = generateRules(found, baskets.size, conf / 100.0, maxObjects)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("Порог достоверности $conf%: правил — ${rules.size}, поиск правил — ${"%.1f".format(Locale.US, ms)} мс")
        saveRules(rules, conf, bySupport)
        labels += "$conf%"
        times += ms
        counts += rules.size
    }
    println()

    writeCharts(labels, times, counts)

    // --- Итоговый список правил (достоверность >= 70%, |X| + |Y| <= 7) ---
    val rules = ordered(generateRules(found, baskets.size, 0.70, maxObjects), bySupport)
    println("СПИСОК ПРАВИЛ (антецедент -> консеквент), суммарно не более $maxObjects объектов,")
    println("достоверность >= 70% (показаны первые 15, полный список — в results2/rules_70pct.csv):")
    for (r in rules.take(15)) {
        println(
            "    {${r.x.sorted().joinToString(", ")}} -> {${r.y.sorted().joinToString(", ")}}  " +
                "support = ${"%.1f".format(Locale.US, r.support * 100)}%, " +
                "confidence = ${"%.1f".format(Locale.US, r.confidence * 100)}%, " +
                "lift = ${"%.2f".format(Locale.US, r.lift)}"
        )
    }
}

// Корзины из csv: строка = корзина, товары через запятую.
// Файл с повреждённой кодировкой (BOM и '€' вместо буквы 'я') — чиним при чтении.
fun readBaskets(path: String): List<Set<String>> =
    File(path).readLines()
        .map { it.removePrefix("\uFEFF").replace("€", "я") }
        .map { it.split(',').map(String::trim).filter { it.isNotEmpty() }.toSet() }
        .filter { it.isNotEmpty() }

/**
 * ECLAT: поиск частых наборов в вертикальном представлении.
 *
 * 1. Строим tidset каждого товара — множество номеров корзин с этим товаром.
 * 2. Частые одиночные товары становятся корнями поиска в глубину.
 * 3. Расширяем текущий набор следующим товаром; поддержка расширения —
 *    размер пересечения tidset'ов. Пересечение меньше порога — ветка
 *    обрезается (все надмножества тоже будут нечастыми).
 */
fun eclat(baskets: List<Set<String>>, minSupport: Int): Map<Set<String>, Int> {
    val found = mutableMapOf<Set<String>, Int>()

    // Вертикальная база: товар -> множество номеров корзин, где он встречается
    val tidsets = sortedMapOf<String, MutableSet<Int>>()
    for ((i, basket) in baskets.withIndex())
        for (item in basket) tidsets.getOrPut(item) { mutableSetOf() }.add(i)

    val roots = tidsets.entries
        .filter { it.value.size >= minSupport }
        .map { (item, tids) -> item to tids.toSet() }

    // Поиск в глубину: prefix — уже набранная часть набора, candidates —
    // товары с порядковым номером больше последнего в prefix, чьё пересечение
    // tidset'ов с prefix ещё проходит порог поддержки.
    fun dfs(prefix: Set<String>, candidates: List<Pair<String, Set<Int>>>) {
        for (i in candidates.indices) {
            val (item, tids) = candidates[i]
            val itemset = prefix + item
            found[itemset] = tids.size
            val next = (i + 1 until candidates.size).mapNotNull { j ->
                val inter = tids.intersect(candidates[j].second)
                if (inter.size >= minSupport) candidates[j].first to inter else null
            }
            if (next.isNotEmpty()) dfs(itemset, next)
        }
    }
    dfs(emptySet(), roots)
    return found
}

// Ассоциативное правило X -> Y с метриками.
data class Rule(
    val x: Set<String>, val y: Set<String>,
    val support: Double, val confidence: Double, val lift: Double,
)

/**
 * Генерация правил из частых наборов (X и Y — разбиение набора на две части):
 *   support    = supp(X ∪ Y) / N       — доля корзин, где есть и X, и Y;
 *   confidence = supp(X ∪ Y) / supp(X) — доля корзин с X, где дополнительно есть Y;
 *   lift       = confidence / P(Y)     — во сколько раз вероятность Y выше при наличии X.
 * Ограничения: достоверность >= minConfidence, |X| + |Y| <= maxObjects.
 */
fun generateRules(
    found: Map<Set<String>, Int>,
    total: Int,
    minConfidence: Double,
    maxObjects: Int,
): List<Rule> {
    val rules = mutableListOf<Rule>()
    for ((itemset, countXY) in found) {
        if (itemset.size < 2 || itemset.size > maxObjects) continue
        val items = itemset.toList()
        for (mask in 1 until (1 shl items.size) - 1) {   // все разбиения набора на X и Y
            val x = items.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val y = itemset - x
            val countX = found[x] ?: continue
            val countY = found[y] ?: continue
            val support = countXY.toDouble() / total
            val confidence = countXY.toDouble() / countX
            val lift = confidence / (countY.toDouble() / total)
            if (confidence >= minConfidence) rules += Rule(x, y, support, confidence, lift)
        }
    }
    return rules
}

/** Упорядочивание списка правил: по убыванию поддержки (с лекс. досыркой) или лексикографическое. */
fun ordered(rules: List<Rule>, bySupport: Boolean): List<Rule> =
    if (bySupport) rules.sortedWith(
        compareByDescending<Rule> { it.support }.thenBy { it.lexKey() }
    ) else rules.sortedBy { it.lexKey() }

fun Rule.lexKey(): String =
    "{${x.sorted().joinToString(", ")}} -> {${y.sorted().joinToString(", ")}}"

// Правила -> results2/rules_<порог>pct.csv
fun saveRules(rules: List<Rule>, confPct: Int, bySupport: Boolean) {
    File("results2").mkdirs()
    File("results2/rules_${confPct}pct.csv").printWriter().use { out ->
        out.println("antecedent;consequent;objects;support;confidence;lift")
        for (r in ordered(rules, bySupport)) {
            out.println(
                "${r.x.sorted().joinToString(",")};${r.y.sorted().joinToString(",")};${r.x.size + r.y.size};" +
                    "${"%.4f".format(Locale.US, r.support)};${"%.4f".format(Locale.US, r.confidence)};${"%.2f".format(Locale.US, r.lift)}"
            )
        }
    }
}

// Диаграммы на Chart.js -> charts_rules.html:
// 1) время поиска правил от порога достоверности, 2) число правил от порога.
fun writeCharts(labels: List<String>, times: List<Double>, counts: List<Int>) {
    val html = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <title>Лабораторная 2. Поиск ассоциативных правил (ECLAT)</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
            <style>body { font-family: sans-serif; max-width: 900px; margin: 30px auto; }</style>
        </head>
        <body>
            <h2>1. Быстродействие поиска правил при изменении порога достоверности</h2>
            <div style="height: 400px;"><canvas id="time"></canvas></div>
            <h2>2. Общее количество найденных правил при изменении порога достоверности</h2>
            <div style="height: 400px;"><canvas id="count"></canvas></div>
            <script>
                const labels = [${labels.joinToString { "\"$it\"" }}];
                new Chart(document.getElementById('time'), {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [{ label: 'время поиска правил, мс', data: [${times.joinToString()}] }]
                    },
                    options: { maintainAspectRatio: false, scales: { y: { beginAtZero: true } } }
                });
                new Chart(document.getElementById('count'), {
                    type: 'bar',
                    data: {
                        labels: labels,
                        datasets: [{ label: 'количество правил', data: [${counts.joinToString()}] }]
                    },
                    options: { maintainAspectRatio: false, scales: { y: { beginAtZero: true } } }
                });
            </script>
        </body>
        </html>
    """.trimIndent()
    File("charts_rules.html").writeText(html)
}
