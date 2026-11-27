import { useState } from "react";
import { safeLink } from "./catalog";
import catalogImages from "./demo-product-images.json";

export default function ProductImage({ item }) {
  const source = safeLink(item.image_url) ?? catalogImages[item.offer_id];
  const [failedSource, setFailedSource] = useState(null);
  return source && source !== failedSource
    ? <img className="product-image" src={source} alt={item.name} loading="lazy" decoding="async" onError={() => setFailedSource(source)} />
    : <span className="image-unavailable">Фото недоступно</span>;
}
