import { Link } from 'react-router-dom';

export function NotFoundPage(): React.ReactElement {
  return (
    <>
      <div className="page-head">
        <div className="od-stack" style={{ '--od-gap': '8px' } as React.CSSProperties}>
          <h1 className="h1">Такой страницы нет</h1>
          <p className="lead">
            Возможно, ссылка устарела или отчёт удалён. Все отчёты — в разделе «Отчёты».
          </p>
        </div>
      </div>
      <div className="od-cluster">
        <Link className="btn btn--primary" to="/radar">
          На радар
        </Link>
        <Link className="btn btn--secondary" to="/reports">
          К отчётам
        </Link>
      </div>
    </>
  );
}
