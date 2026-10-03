@file:OptIn(ExperimentalMaterial3Api::class)

package com.meatsuitdiagnostics.app.ui.answer

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.domain.Answer
import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.domain.QuestionSpec
import com.meatsuitdiagnostics.app.domain.validate
import com.meatsuitdiagnostics.app.ui.common.BackButton
import com.meatsuitdiagnostics.app.ui.question.QuestionInput
import kotlinx.coroutines.launch

/** Pick any current question to answer outside a check-in. */
@Composable
fun AnswerNowScreen(container: AppContainer, onPick: (Int) -> Unit, onBack: () -> Unit) {
    val questions by container.config.questions.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = { TopAppBar(title = { Text("Answer a question") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (questions.isEmpty()) {
            Text(
                "There are no questions yet. Create some in the admin web app, then sync.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
        LazyColumn(Modifier.padding(padding)) {
            items(questions.filter { it.spec !is QuestionSpec.Unsupported }, key = { it.id }) { question ->
                Text(
                    question.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(question.id) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

class QuestionAnswerViewModel(private val container: AppContainer, questionId: Int) : ViewModel() {
    var question by mutableStateOf<Question?>(null)
        private set
    var answer by mutableStateOf<Answer?>(null)
    var saved by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { question = container.config.question(questionId) }
    }

    val canSubmit: Boolean
        get() {
            val q = question ?: return false
            val a = answer ?: return false
            return q.spec.validate(a) == null
        }

    fun submit() {
        val q = question ?: return
        val a = answer ?: return
        viewModelScope.launch {
            container.checkins.submitAdHoc(q, a)
            saved = true
        }
    }
}

@Composable
fun QuestionAnswerScreen(container: AppContainer, questionId: Int, onBack: () -> Unit, onDone: () -> Unit) {
    val vm: QuestionAnswerViewModel = viewModel(key = "q$questionId") { QuestionAnswerViewModel(container, questionId) }
    val context = LocalContext.current
    LaunchedEffect(vm.saved) {
        if (vm.saved) {
            Toast.makeText(context, "Answer saved", Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Answer now") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        val question = vm.question ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(question.text, style = MaterialTheme.typography.headlineSmall)
                QuestionInput(question, vm.answer, onChange = { vm.answer = it })
            }
            Button(
                onClick = vm::submit,
                enabled = vm.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) { Text("Save answer") }
        }
    }
}
