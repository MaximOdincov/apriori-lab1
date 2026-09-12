import java.io.File
import java.util.Locale
import kotlin.math.ceil

fun main() {
    val baskets = readBaskets("src/data/baskets.csv")
    val minConfidence = 0.3     // порог достоверности для ассоциативных правил
    println("Корзин: ${baskets.size}, разных товаров: ${baskets.flatten().distinct().size}")

    apriori(baskets.take(200), 3)   // прогрев JVM, чтобы не портить первый замер

    val thresholds = listOf(0.01, 0.03, 0.05, 0.10, 0.15)
    val labels = mutableListOf<String>()
    val times = mutableListOf<Long>()
    val counts = mutableListOf<Map<Int, Int>>()

    for (t in thresholds) {
        val minSupport = ceil(t * baskets.size).toInt()

        val start = System.nanoTime()
        val found = apriori(baskets, minSupport)
        val ms = (System.nanoTime() - start) / 1_000_000

        val byLength = found.keys.groupingBy { it.size }.eachCount().toSortedMap()
        val details = byLength.entries.joinToString(", ") { (len, n) -> "длина $len: $n" }
        val pct = (t * 100).toInt()
        println("Порог $pct% (поддержка >= $minSupport): ${found.size} наборов ($details), время $ms мс")

        // Результирующий список: каждый набор + значение его поддержки
        found.entries.sortedByDescending { it.value }.forEach { (set, count) ->
            println("    {${set.sorted().joinToString(", ")}} — поддержка $count")
        }

        // Правила X -> Y с метриками support, confidence, lift (уже вне замера времени)
        val rules = generateRules(found, baskets.size, minConfidence)
        saveRules(rules, pct)
        println("    Правила (confidence >= $minConfidence): ${rules.size}, топ-5 по lift:")
        for (r in rules.take(5)) {
            println(
                "      {${r.x.sorted().joinToString(", ")}} -> {${r.y.sorted().joinToString(", ")}}  " +
                    "support = ${"%.1f".format(Locale.US, r.support * 100)}%, " +
                    "confidence = ${"%.1f".format(Locale.US, r.confidence * 100)}%, " +
                    "lift = ${"%.2f".format(Locale.US, r.lift)}"
            )
        }
        println()

        saveResult(found, pct)
        labels += "$pct%"
        times += ms
        counts += byLength
    }

    writeCharts(labels, times, counts)
    println("\nСписки наборов: results/, диаграммы: charts.html (открыть в браузере)")
}

// Корзины из csv: строка = корзина, товары через запятую.
// Файл с повреждённой кодировкой (BOM и '€' вместо буквы 'я') — чиним при чтении.
fun readBaskets(path: String): List<Set<String>> =
    File(path).readLines()
        .map { it.removePrefix("\uFEFF").replace("€", "я") }
        .map { it.split(',').map(String::trim).filter { it.isNotEmpty() }.toSet() }
        .filter { it.isNotEmpty() }

// Частые наборы с поддержкой -> results/apriori_<порог>pct.csv (по убыванию поддержки)
fun saveResult(found: Map<Set<String>, Int>, pct: Int) {
    File("results").mkdirs()
    File("results/apriori_${pct}pct.csv").printWriter().use { out ->
        out.println("itemset;support")
        found.entries.sortedByDescending { it.value }.forEach { (set, count) ->
            out.println("${set.sorted().joinToString(",")};$count")
        }
    }
}

// Диаграммы на Chart.js -> charts.html:
// 1) время работы от порога поддержки, 2) число наборов каждой длины от порога.
fun writeCharts(labels: List<String>, times: List<Long>, counts: List<Map<Int, Int>>) {
    val maxLen = counts.maxOf { it.keys.maxOrNull() ?: 0 }
    val datasets = (1..maxLen).joinToString(",\n") { len ->
        "{ label: 'наборы длины $len', data: [${counts.joinToString { (it[len] ?: 0).toString() }}] }"
    }
    val html = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <title>Эксперименты с алгоритмом Apriori</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
            <style>body { font-family: sans-serif; max-width: 900px; margin: 30px auto; }</style>
        </head>
        <body>
            <h2>1. Быстродействие при изменении порога поддержки</h2>
            <div style="height: 400px;"><canvas id="time"></canvas></div>
            <h2>2. Число частых наборов разной длины при изменении порога поддержки</h2>
            <div style="height: 400px;"><canvas id="count"></canvas></div>
            <script>
                const labels = [${labels.joinToString { "\"$it\"" }}];
                new Chart(document.getElementById('time'), {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [{ label: 'время работы, мс', data: [${times.joinToString()}] }]
                    },
                    options: { maintainAspectRatio: false, scales: { y: { beginAtZero: true } } }
                });
                new Chart(document.getElementById('count'), {
                    type: 'bar',
                    data: { labels: labels, datasets: [
${datasets}
                    ] },
                    options: { maintainAspectRatio: false, scales: { y: { beginAtZero: true } } }
                });
            </script>
        </body>
        </html>
    """.trimIndent()
    File("charts.html").writeText(html)
}

// Ассоциативное правило X -> Y с тремя метриками.
data class Rule(val x: Set<String>, val y: Set<String>, val support: Double, val confidence: Double, val lift: Double)

// Генерация правил из частых наборов (X и Y — разбиение частого набора на две части):
//   support    = supp(X ∪ Y) / N            — доля корзин, где есть и X, и Y;
//   confidence = supp(X ∪ Y) / supp(X)      — доля корзин с X, где дополнительно есть Y;
//   lift       = confidence / (supp(Y) / N) — во сколько раз вероятность Y выше при наличии X.
fun generateRules(found: Map<Set<String>, Int>, total: Int, minConfidence: Double): List<Rule> {
    val rules = mutableListOf<Rule>()
    for ((itemset, countXY) in found) {
        if (itemset.size < 2) continue
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
    return rules.sortedByDescending { it.lift }
}

// Правила -> results/rules_<порог>pct.csv
fun saveRules(rules: List<Rule>, pct: Int) {
    File("results/rules_${pct}pct.csv").printWriter().use { out ->
        out.println("antecedent;consequent;support;confidence;lift")
        for (r in rules) {
            out.println(
                "${r.x.sorted().joinToString(",")};${r.y.sorted().joinToString(",")};" +
                    "${"%.4f".format(Locale.US, r.support)};${"%.4f".format(Locale.US, r.confidence)};${"%.2f".format(Locale.US, r.lift)}"
            )
        }
    }
}

// Apriori: строит частые наборы уровень за уровнем (L1 -> L2 -> ...),
// пока очередной уровень не опустеет.
fun apriori(baskets: List<Set<String>>, minSupport: Int): Map<Set<String>, Int> {
    // L1: сколько раз встречается каждый товар
    var level = baskets.flatten()
        .groupingBy { it }
        .eachCount()
        .filterValues { it >= minSupport }
        .mapKeys { (item, _) -> setOf(item) }

    val found = mutableMapOf<Set<String>, Int>()
    while (level.isNotEmpty()) {
        found += level
        level = candidates(level.keys)
            .associateWith { candidate -> baskets.count { basket -> candidate.all { it in basket } } }
            .filterValues { it >= minSupport }
    }
    return found
}

// Кандидаты C(k) из частых L(k-1):
// JOIN  — склеиваем пары наборов с одинаковым префиксом длины k-2;
// PRUNE — отбрасываем кандидата, у которого есть нечастый (k-1)-поднабор.
fun candidates(frequent: Set<Set<String>>): List<Set<String>> {
    val sorted = frequent.map { it.sorted() }
    val result = mutableListOf<Set<String>>()
    for (a in sorted) for (b in sorted) {
        if (a.dropLast(1) == b.dropLast(1) && a.last() < b.last()) {
            val candidate = (a + b.last()).toSet()
            if (candidate.all { item -> candidate - item in frequent }) result += candidate
        }
    }
    return result
}
