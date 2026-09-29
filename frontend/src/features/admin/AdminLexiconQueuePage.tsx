/**
 * Очередь пополнения перекрёстного словаря направлений — открыта всем: входа нет.
 *
 * Журнал формулировок, которых словарь не знает, собирался с самого начала, а экрана к нему не
 * было — единственная возможность API, до которой не доходил ни один путь из интерфейса. Данные
 * копились, и посмотреть на них было негде.
 *
 * Цена этого измерена на самом продукте: пробел в словаре в этой сессии находили дважды и оба раза
 * случайно. «Кибербезопасность» по-английски не разрешалась ни в одну метку — направление
 * объявлялось нераспознанным на корпусе, где 209 документов размечены как безопасность.
 * «Биотехнологии» не разрешались ни на одном языке. Обе дыры журнал видел; их не видел человек.
 *
 * Экран не правит словарь: правка курируется и проходит ревью (ADR о детерминизме запрещает
 * машинный перевод). Он отвечает на вопрос «что чинить первым», и порядок здесь — суть, а не
 * оформление.
 */
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import { Card } from '@/components/Card';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import { PageHeader, PageStack } from '@/components/PageHeader';
import { SkeletonList } from '@/components/Skeleton';
import { Table, Td, Th } from '@/components/Table';
import { formatDateTime, toDateTimeAttr } from '@/lib/format';
import { useT } from '@/lib/i18n/useT';
import { queueOrder } from './lexiconQueue';

const LIMIT = 100;

export function AdminLexiconQueuePage(): React.ReactElement {
  const t = useT();
  const query = useQuery({
    queryKey: queryKeys.unrecognizedDirections(LIMIT),
    queryFn: ({ signal }) => api.listUnrecognizedDirections(LIMIT, signal),
  });

  return (
    <PageStack>
      <PageHeader title={t('lexicon.title')} description={t('lexicon.description')} />

      {query.isPending ? <SkeletonList count={6} label={t('lexicon.title')} /> : null}
      {query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}

      {query.isSuccess && query.data.length === 0 ? (
        // Пустая очередь — утверждение, а не отсутствие данных: словарь покрывает всё, о чём
        // спрашивали. Показать здесь пустую таблицу значило бы промолчать о хорошей новости.
        <EmptyState title={t('lexicon.emptyTitle')} description={t('lexicon.emptyBody')} />
      ) : null}

      {query.isSuccess && query.data.length > 0 ? (
        <Card>
          <Table caption={t('lexicon.title')}>
            <thead>
              <tr>
                <Th>{t('lexicon.columnQuery')}</Th>
                <Th>{t('lexicon.columnOccurrences')}</Th>
                <Th>{t('lexicon.columnWayOut')}</Th>
                <Th>{t('lexicon.columnSuggestions')}</Th>
                <Th>{t('lexicon.columnLastSeen')}</Th>
              </tr>
            </thead>
            <tbody>
              {queueOrder(query.data).map((row) => (
                <tr key={row.normalizedQuery}>
                  <Td>{row.query}</Td>
                  <Td>{row.occurrences}</Td>
                  <Td>
                    {row.hadAWayOut ? (
                      t('lexicon.wayOutYes')
                    ) : (
                      <strong>{t('lexicon.wayOutNo')}</strong>
                    )}
                  </Td>
                  <Td>
                    {row.suggestions && row.suggestions.length > 0
                      ? row.suggestions.join(', ')
                      : t('lexicon.noSuggestions')}
                  </Td>
                  <Td>
                    <time dateTime={toDateTimeAttr(row.lastSeen)}>
                      {formatDateTime(row.lastSeen)}
                    </time>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      ) : null}
    </PageStack>
  );
}
