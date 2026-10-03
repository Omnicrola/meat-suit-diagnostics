@file:OptIn(ExperimentalMaterial3Api::class)

package com.meatsuitdiagnostics.app.ui.checkin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.data.AnswerState
import com.meatsuitdiagnostics.app.data.db.InstanceEntity
import com.meatsuitdiagnostics.app.domain.Answer
import com.meatsuitdiagnostics.app.domain.Question
import com.meatsuitdiagnostics.app.domain.validate
import com.meatsuitdiagnostics.app.ui.common.BackButton
import com.meatsuitdiagnostics.app.ui.common.answerSummary
import com.meatsuitdiagnostics.app.ui.common.formatTime
import com.meatsuitdiagnostics.app.ui.question.QuestionInput
import kotlinx.coroutines.launch

data class CheckinState(
    val loading: Boolean = true,
    val title: String = "",
    val closesAt: Long = 0,
    val questions: List<Question> = emptyList(),
    /** Answers confirmed with Next or Skip. */
    val answers: Map<Int, AnswerState> = emptyMap(),
    /** Input on the current page that hasn't been confirmed yet; null means incomplete. */
    val editing: Map<Int, Answer?> = emptyMap(),
    /** The question being shown; questions.size means the review page. */
    val index: Int = 0,
    /** Set when the check-in can't be answered (expired, already done, or unknown). */
    val closedMessage: String? = null,
    val submitted: Boolean = false,
) {
    val reviewing: Boolean get() = index >= questions.size
    val current: Question? get() = questions.getOrNull(index)

    /** What the current page's input should show. */
    fun valueFor(question: Question): Answer? =
        if (question.id in editing) editing[question.id] else (answers[question.id] as? AnswerState.Answered)?.answer
}

class CheckinViewModel(private val container: AppContainer, private val instanceId: String) : ViewModel() {
    var state by mutableStateOf(CheckinState())
        private set

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        container.checkins.expireDue()
        val open = container.checkins.openForAnswering(instanceId)
        state = if (open == null) {
            CheckinState(loading = false, closedMessage = closedMessage(container.checkins.instance(instanceId)))
        } else {
            val firstUnanswered = open.questions.indexOfFirst { it.id !in open.drafts }
            CheckinState(
                loading = false,
                title = open.instance.checkinName,
                closesAt = open.instance.expiresAt,
                questions = open.questions,
                answers = open.drafts,
                index = if (firstUnanswered == -1) open.questions.size else firstUnanswered,
            )
        }
    }

    fun onInput(answer: Answer?) {
        val question = state.current ?: return
        state = state.copy(editing = state.editing + (question.id to answer))
    }

    fun canProceed(): Boolean {
        val question = state.current ?: return false
        val value = state.valueFor(question) ?: return false
        return question.spec.validate(value) == null
    }

    fun next() {
        val question = state.current ?: return
        val value = state.valueFor(question) ?: return
        if (question.spec.validate(value) != null) return
        record(question, AnswerState.Answered(value))
    }

    fun skip() {
        record(state.current ?: return, AnswerState.Skipped)
    }

    fun back() {
        if (state.index > 0) state = state.copy(index = state.index - 1)
    }

    fun edit(index: Int) {
        state = state.copy(index = index)
    }

    fun submit() {
        viewModelScope.launch {
            val ok = container.checkins.submit(instanceId, state.answers)
            state = if (ok) {
                state.copy(submitted = true)
            } else {
                state.copy(closedMessage = closedMessage(container.checkins.instance(instanceId)))
            }
        }
    }

    private fun record(question: Question, answer: AnswerState) {
        val answers = state.answers + (question.id to answer)
        // Go on to the next question that still needs an answer, or to the review page.
        val nextIndex = state.questions.indices
            .firstOrNull { it > state.index && state.questions[it].id !in answers }
            ?: state.questions.size
        state = state.copy(answers = answers, editing = state.editing - question.id, index = nextIndex)
        viewModelScope.launch { container.checkins.saveDraft(instanceId, question.id, answer) }
    }

    private fun closedMessage(instance: InstanceEntity?): String = when {
        instance == null -> "This check-in no longer exists."
        instance.status == InstanceEntity.STATUS_EXPIRED ->
            "This check-in closed at ${formatTime(instance.expiresAt)}. Anything you hadn't answered was recorded as missed."
        instance.status == InstanceEntity.STATUS_COMPLETED -> "You've already answered this check-in."
        else -> "This check-in is no longer open."
    }
}

@Composable
fun CheckinScreen(container: AppContainer, instanceId: String, onClose: () -> Unit) {
    val vm: CheckinViewModel = viewModel(key = instanceId) { CheckinViewModel(container, instanceId) }
    val state = vm.state

    LaunchedEffect(state.submitted) { if (state.submitted) onClose() }
    // System back steps back through the questions before leaving (answers so far are saved either way).
    BackHandler(enabled = state.index > 0 && state.closedMessage == null) { vm.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title.ifEmpty { "Check-in" })
                        if (state.questions.isNotEmpty() && state.closedMessage == null) {
                            Text(
                                "Closes at ${formatTime(state.closesAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onClose) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when {
                state.loading -> Centered { CircularProgressIndicator(Modifier.padding(24.dp)) }
                state.closedMessage != null -> ClosedPage(state.closedMessage, onClose)
                state.reviewing -> ReviewPage(state, onEdit = vm::edit, onSubmit = vm::submit)
                else -> QuestionPage(state, vm)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun ClosedPage(message: String, onClose: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClose) { Text("OK") }
    }
}

@Composable
private fun QuestionPage(state: CheckinState, vm: CheckinViewModel) {
    val question = state.current ?: return
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { state.index.toFloat() / state.questions.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Question ${state.index + 1} of ${state.questions.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(question.text, style = MaterialTheme.typography.headlineSmall)
            QuestionInput(question, state.valueFor(question), onChange = vm::onInput)
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.index > 0) TextButton(onClick = vm::back) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::skip) { Text("Skip") }
            Spacer(Modifier.padding(6.dp))
            Button(onClick = vm::next, enabled = vm.canProceed()) {
                Text(if (state.index == state.questions.lastIndex) "Review" else "Next")
            }
        }
    }
}

@Composable
private fun ReviewPage(state: CheckinState, onEdit: (Int) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Review", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap an answer to change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.questions.forEachIndexed { index, question ->
                val summary = when (val answer = state.answers[question.id]) {
                    is AnswerState.Answered -> answerSummary(question.spec, answer.answer)
                    AnswerState.Skipped, null -> "Skipped"
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(index) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(question.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) { Text("Submit") }
    }
}
