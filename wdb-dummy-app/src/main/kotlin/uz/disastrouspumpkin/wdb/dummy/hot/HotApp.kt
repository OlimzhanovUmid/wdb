package uz.disastrouspumpkin.wdb.dummy.hot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.singleWindowApplication

/**
 * Compose Hot Reload fixture (add-compose-hot-reload). A single big label.
 * On a wall machine, launch this in hot mode, edit [message] below, run `wdb reload`,
 * and the text updates live without the window closing. Verified live on wall-02.
 */
fun main() = singleWindowApplication(title = "wdb hot-reload fixture") {
    HotContent()
}

@Composable
fun HotContent() {
    // Edit this line and run `wdb reload <classesDir> <machine>` to see it change live.
    val message = "hot reload: v5"
    var counter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically)) {
        BasicText(message, modifier = Modifier.testTag("hot-message"))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Blue)) {
                append("Pos")
            }
            append("point")
//            append("man $counter")
        }, fontSize = 60.sp, textAlign = TextAlign.Center)
        Button(onClick = {
            counter++
        }, modifier = Modifier.testTag("hot-button")) {
            Text("Clicked on me $counter times!")
        }
        var input by rememberSaveable { mutableStateOf("") }
        TextField(value = input, onValueChange = { input = it }, label = { Text("devtools set-text target") }, modifier = Modifier.testTag("hot-textfield"))
        // Scrollable list — devtools scroll target.
        LazyColumn(Modifier.fillMaxWidth().height(200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items((1..100).toList()) { i ->
                Text("row #$i", fontSize = 22.sp)
            }
        }
    }
}
