import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import {
  Send,
  Trash2,
  Sparkles,
  AlertTriangle,
  X,
} from 'lucide-react';
import { useChatStore, type ChatUiMessage } from '@/stores/chatStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useTaskStore } from '@/stores/taskStore';
import { useBatchStore } from '@/stores/batchStore';
import { useAuthStore } from '@/stores/authStore';
import { useProFeature } from '@/hooks/useProFeature';
import { ProUpgradeModal } from '@/components/shared/ProUpgradeModal';
import type { ChatActionPayload } from '@/types/chat';
import {
  actionLabel,
  actionSignature,
  executeChatAction,
} from '@/features/chat/chatActions';
import { Button } from '@/components/ui/Button';

const STARTER_PROMPTS: string[] = [
  'What should I focus on today?',
  'Help me reschedule overdue tasks',
  'Break down my biggest task',
  'Suggest a 25-minute focus session',
];

export function ChatScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { isPro, showUpgrade, setShowUpgrade } = useProFeature();
  const aiFeaturesEnabled = useSettingsStore((s) => s.aiFeaturesEnabled);
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);

  const messages = useChatStore((s) => s.messages);
  const isSending = useChatStore((s) => s.isSending);
  const showDisclosure = useChatStore((s) => s.showDisclosure);
  const showClearConfirm = useChatStore((s) => s.showClearConfirm);
  const error = useChatStore((s) => s.error);
  const initialize = useChatStore((s) => s.initialize);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const dismissDisclosure = useChatStore((s) => s.dismissDisclosure);
  const requestClearConversation = useChatStore(
    (s) => s.requestClearConversation,
  );
  const dismissClearConfirm = useChatStore((s) => s.dismissClearConfirm);
  const confirmClearAndPersistSkip = useChatStore(
    (s) => s.confirmClearAndPersistSkip,
  );
  const clearError = useChatStore((s) => s.clearError);
  const actionsInFlight = useChatStore((s) => s.actionsInFlight);
  const setActionInFlight = useChatStore((s) => s.setActionInFlight);

  const updateTask = useTaskStore((s) => s.updateTask);
  const completeTask = useTaskStore((s) => s.completeTask);
  const deleteTask = useTaskStore((s) => s.deleteTask);
  const setPendingBatchCommand = useBatchStore((s) => s.setPendingCommand);

  const [inputText, setInputText] = useState('');
  const [dontAskAgain, setDontAskAgain] = useState(false);
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    void initialize(startOfDayHour);
  }, [initialize, startOfDayHour]);

  // Auto-scroll on new messages.
  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages.length, isSending]);

  // Surface errors via toast then clear.
  useEffect(() => {
    if (error) {
      toast.error(error);
      clearError();
    }
  }, [error, clearError]);

  const aiDisabled = !aiFeaturesEnabled;
  const proDisabled = !isPro;
  const sendDisabled =
    aiDisabled || proDisabled || isSending || inputText.trim().length === 0;

  const handleSend = useCallback(() => {
    if (!isPro) {
      setShowUpgrade(true);
      return;
    }
    if (sendDisabled) return;
    const text = inputText.trim();
    setInputText('');
    void sendMessage(text);
  }, [isPro, setShowUpgrade, sendDisabled, inputText, sendMessage]);

  const handleStarterPrompt = useCallback(
    (prompt: string) => {
      if (!isPro) {
        setShowUpgrade(true);
        return;
      }
      setInputText('');
      void sendMessage(prompt);
    },
    [isPro, setShowUpgrade, sendMessage],
  );

  const handleActionClick = useCallback(
    async (action: ChatActionPayload) => {
      const sig = actionSignature(action);
      if (!sig) return;
      if (actionsInFlight.has(sig)) return;
      setActionInFlight(sig, true);
      try {
        const result = await executeChatAction(action, {
          updateTask,
          completeTask,
          deleteTask,
          setPendingBatchCommand,
          navigate,
        });
        if (result?.message) {
          toast.success(result.message);
        }
      } catch {
        toast.error('Action failed');
      } finally {
        setActionInFlight(sig, false);
      }
    },
    [
      actionsInFlight,
      setActionInFlight,
      updateTask,
      completeTask,
      deleteTask,
      setPendingBatchCommand,
      navigate,
    ],
  );

  const subtitle = useMemo(() => {
    if (aiDisabled) return 'AI Features Disabled';
    if (proDisabled) return 'Pro Feature';
    if (!user) return 'General';
    return 'General';
  }, [aiDisabled, proDisabled, user]);

  return (
    <div className="flex h-[calc(100vh-4rem)] flex-col bg-[var(--color-bg-primary)]">
      <header className="flex items-center justify-between border-b border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-4 py-3">
        <div className="flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-[var(--color-accent)]" />
          <div>
            <h1 className="text-base font-semibold text-[var(--color-text-primary)]">
              AI Coach
            </h1>
            <p className="text-xs text-[var(--color-text-secondary)]">
              {subtitle}
            </p>
          </div>
        </div>
        {messages.length > 0 && (
          <button
            type="button"
            onClick={requestClearConversation}
            aria-label="Clear Chat"
            className="rounded-md p-2 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)] hover:text-[var(--color-text-primary)]"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </header>

      {aiDisabled && (
        <div className="flex items-center gap-2 border-b border-amber-500/30 bg-amber-500/10 px-4 py-2 text-xs text-amber-700 dark:text-amber-400">
          <AlertTriangle className="h-3.5 w-3.5" />
          <span>
            AI Features are off. Enable them in{' '}
            <button
              type="button"
              className="underline"
              onClick={() => navigate('/settings')}
            >
              Settings
            </button>
            .
          </span>
        </div>
      )}

      {!aiDisabled && proDisabled && (
        <div className="flex items-center gap-2 border-b border-amber-500/30 bg-amber-500/10 px-4 py-2 text-xs text-amber-700 dark:text-amber-400">
          <AlertTriangle className="h-3.5 w-3.5" />
          <span>
            AI Coach is a Pro feature.{' '}
            <button
              type="button"
              className="underline"
              onClick={() => setShowUpgrade(true)}
            >
              Upgrade to Pro
            </button>{' '}
            to start chatting.
          </span>
        </div>
      )}

      <div
        ref={listRef}
        className="flex-1 overflow-y-auto px-4 py-3"
        data-testid="chat-message-list"
      >
        {messages.length === 0 && !isSending && (
          <WelcomeCard
            disabled={aiDisabled || proDisabled}
            onStarterPrompt={handleStarterPrompt}
          />
        )}

        <ul className="space-y-3">
          {messages.map((m) => (
            <li key={m.id}>
              <ChatBubble
                message={m}
                onActionClick={(a) => void handleActionClick(a)}
                disabledActionSignatures={actionsInFlight}
              />
            </li>
          ))}
          {isSending && (
            <li>
              <TypingBubble />
            </li>
          )}
        </ul>
      </div>

      <ChatInputBar
        value={inputText}
        onChange={setInputText}
        onSend={handleSend}
        disabled={aiDisabled || proDisabled || isSending}
        sendDisabled={sendDisabled}
      />

      <ProUpgradeModal
        isOpen={showUpgrade}
        onClose={() => setShowUpgrade(false)}
        featureName="AI Coach"
        featureDescription="Chat with an AI coach about your tasks, plans, and what feels stuck."
      />

      {showDisclosure && (
        <Modal
          title="AI Chat"
          onClose={dismissDisclosure}
          actions={
            <Button onClick={dismissDisclosure} variant="primary">
              Got It
            </Button>
          }
        >
          <p>
            Your messages are processed by AI to provide coaching, along with
            the last few turns of conversation for context. When chat is opened
            from a task, the AI also sees that task's title, description, due
            date, priority, project name, and completion state. Conversations
            are now saved to your PrismTask account so you can pick up the
            thread on any signed-in device, and stay until you delete them.
            The AI service itself doesn't keep your messages beyond answering
            your prompt.
          </p>
        </Modal>
      )}

      {showClearConfirm && (
        <Modal
          title="Clear Chat?"
          onClose={dismissClearConfirm}
          actions={
            <div className="flex gap-2">
              <Button onClick={dismissClearConfirm} variant="secondary">
                Cancel
              </Button>
              <Button
                onClick={() => {
                  confirmClearAndPersistSkip(dontAskAgain);
                  setDontAskAgain(false);
                }}
                variant="primary"
              >
                Clear
              </Button>
            </div>
          }
        >
          <p className="text-sm">
            This will delete all messages in the current conversation. This
            can't be undone.
          </p>
          <label className="mt-3 flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={dontAskAgain}
              onChange={(e) => setDontAskAgain(e.target.checked)}
            />
            Don't Ask Again
          </label>
        </Modal>
      )}
    </div>
  );
}

function WelcomeCard({
  onStarterPrompt,
  disabled,
}: {
  onStarterPrompt: (prompt: string) => void;
  disabled: boolean;
}) {
  return (
    <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-bg-secondary)] p-4">
      <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
        Hey there
      </h2>
      <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
        I can help you break down tasks, plan your day, or just talk through
        what feels stuck. What's on your mind?
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        {STARTER_PROMPTS.map((prompt) => (
          <button
            key={prompt}
            type="button"
            disabled={disabled}
            onClick={() => onStarterPrompt(prompt)}
            className="rounded-full border border-[var(--color-accent)]/50 bg-[var(--color-accent)]/10 px-3 py-1 text-xs font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/20 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  );
}

function ChatBubble({
  message,
  onActionClick,
  disabledActionSignatures,
}: {
  message: ChatUiMessage;
  onActionClick: (action: ChatActionPayload) => void;
  disabledActionSignatures: Set<string>;
}) {
  const isUser = message.role === 'user';
  return (
    <div className={isUser ? 'flex justify-end' : 'flex justify-start'}>
      <div className="max-w-[80%]">
        <div
          className={
            isUser
              ? 'rounded-2xl rounded-br-md bg-[var(--color-accent)] px-3.5 py-2.5 text-sm text-white shadow-sm'
              : 'rounded-2xl rounded-bl-md bg-[var(--color-bg-secondary)] px-3.5 py-2.5 text-sm text-[var(--color-text-primary)] shadow-sm'
          }
        >
          <p className="whitespace-pre-wrap">{message.text}</p>
        </div>
        {!isUser && message.actions.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-2">
            {message.actions.map((action, idx) => {
              const sig = actionSignature(action);
              const disabled = sig != null && disabledActionSignatures.has(sig);
              return (
                <button
                  key={`${action.type}-${idx}`}
                  type="button"
                  disabled={disabled}
                  onClick={() => onActionClick(action)}
                  className="rounded-full bg-[var(--color-accent)]/15 px-3 py-1 text-xs font-medium text-[var(--color-accent)] transition hover:bg-[var(--color-accent)]/25 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {actionLabel(action)}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function TypingBubble() {
  return (
    <div className="flex justify-start">
      <div className="rounded-2xl rounded-bl-md bg-[var(--color-bg-secondary)] px-3.5 py-2.5">
        <div className="flex gap-1">
          {[0, 1, 2].map((i) => (
            <span
              key={i}
              className="inline-block h-2 w-2 animate-pulse rounded-full bg-[var(--color-text-secondary)]"
              style={{ animationDelay: `${i * 150}ms` }}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function ChatInputBar({
  value,
  onChange,
  onSend,
  disabled,
  sendDisabled,
}: {
  value: string;
  onChange: (next: string) => void;
  onSend: () => void;
  disabled: boolean;
  sendDisabled: boolean;
}) {
  return (
    <form
      className="flex items-end gap-2 border-t border-[var(--color-border)] bg-[var(--color-bg-secondary)] px-3 py-2"
      onSubmit={(e) => {
        e.preventDefault();
        onSend();
      }}
    >
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            onSend();
          }
        }}
        placeholder="What's on your mind?"
        rows={1}
        disabled={disabled}
        className="max-h-32 flex-1 resize-none rounded-2xl border border-[var(--color-border)] bg-[var(--color-bg-primary)] px-3 py-2 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)] disabled:cursor-not-allowed disabled:opacity-60"
      />
      <button
        type="submit"
        disabled={sendDisabled}
        aria-label="Send"
        className="rounded-full bg-[var(--color-accent)] p-2 text-white shadow-sm transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        <Send className="h-4 w-4" />
      </button>
    </form>
  );
}

function Modal({
  title,
  onClose,
  children,
  actions,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  actions: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4">
      <div className="w-full max-w-md rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-primary)] p-5 shadow-xl">
        <div className="mb-3 flex items-start justify-between">
          <h2 className="text-base font-semibold text-[var(--color-text-primary)]">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-tertiary)]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="text-sm text-[var(--color-text-primary)]">
          {children}
        </div>
        <div className="mt-4 flex justify-end">{actions}</div>
      </div>
    </div>
  );
}

